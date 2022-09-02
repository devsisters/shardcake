package com.devsisters.shardcake

import com.devsisters.shardcake.Messenger.Replier
import com.devsisters.shardcake.Sharding.EntityState
import com.devsisters.shardcake.errors.{ EntityNotManagedByThisPod, PodUnavailable, SendTimeoutException }
import com.devsisters.shardcake.interfaces.Pods.BinaryMessage
import com.devsisters.shardcake.interfaces.{ Logging, Pods, Serialization, Storage }
import com.devsisters.shardcake.internal.EntityManager
import zio._
import zio.duration._
import zio.clock.Clock
import zio.random.Random
import zio.stream.ZStream

import java.time.OffsetDateTime

/**
 * A component that takes care of communicating with sharded entities.
 * See the companion object for layer creation and public methods.
 */
class Sharding private (
  address: PodAddress,
  config: Config,
  shardAssignments: Ref[Map[ShardId, PodAddress]],
  entityStates: Ref[Map[String, EntityState]],
  singletons: RefM[List[(String, UIO[Nothing], Option[Fiber[Nothing, Nothing]])]],
  replyPromises: RefM[Map[String, Promise[Throwable, Option[Any]]]], // promise for each pending reply,
  lastUnhealthyNodeReported: Ref[OffsetDateTime],
  isShuttingDownRef: Ref[Boolean],
  shardManager: ShardManagerClient,
  pods: Pods,
  storage: Storage,
  logger: Logging,
  random: Random.Service,
  clock: Clock.Service,
  serialization: Serialization
) { self =>
  private[shardcake] def getShardId(entityId: String): ShardId =
    math.abs(entityId.hashCode % config.numberOfShards) + 1

  val register: Task[Unit] =
    logger.logDebug(s"Registering pod $address to Shard Manager") *>
      shardManager.register(address)

  val unregister: Task[Unit] =
    logger.logDebug(s"Stopping local entities") *>
      isShuttingDownRef.set(true) *>
      entityStates.get.flatMap(states => ZIO.foreach_(states.values)(_.entityManager.terminateAllEntities)) *>
      logger.logDebug(s"Unregistering pod $address to Shard Manager") *>
      shardManager.unregister(address)

  private def isSingletonNode: UIO[Boolean] =
    // Start singletons on the pod hosting shard 1.
    shardAssignments.get.map(_.get(1).contains(address))

  private def startSingletonsIfNeeded: UIO[Unit] =
    ZIO
      .whenM(isSingletonNode) {
        singletons.update { singletons =>
          ZIO.foreach(singletons) {
            case (name, run, None) =>
              logger.logDebug(s"Starting singleton $name") *> run.forkDaemon.map(fiber => (name, run, Some(fiber)))
            case other             => ZIO.succeed(other)
          }
        }
      }

  private def stopSingletonsIfNeeded: UIO[Unit] =
    ZIO
      .unlessM(isSingletonNode) {
        singletons.update { singletons =>
          ZIO.foreach(singletons) {
            case (name, run, Some(fiber)) =>
              logger.logDebug(s"Stopping singleton $name") *> fiber.interrupt.as((name, run, None))
            case other                    => ZIO.succeed(other)
          }
        }
      }

  def registerSingleton(name: String, run: UIO[Nothing]): UIO[Unit] =
    singletons.update(list => ZIO.succeed((name, run, None) :: list)) *> startSingletonsIfNeeded

  private[shardcake] def assign(shards: Set[ShardId]): UIO[Unit] =
    ZIO
      .unlessM(isShuttingDown) {
        shardAssignments.update(shards.foldLeft(_) { case (map, shard) => map.updated(shard, address) }) *>
          startSingletonsIfNeeded <*
          logger.logDebug(s"Assigned shards: $shards")
      }

  private[shardcake] def unassign(shards: Set[ShardId]): UIO[Unit] =
    shardAssignments.update(shards.foldLeft(_) { case (map, shard) =>
      if (map.get(shard).contains(address)) map - shard else map
    }) *>
      logger.logDebug(s"Unassigning shards: $shards") *>
      entityStates.get.flatMap(state =>
        ZIO.foreach_(state.values)(
          _.entityManager.terminateEntitiesOnShards(shards) // this will return once all shards are terminated
        )
      ) *>
      stopSingletonsIfNeeded <*
      logger.logDebug(s"Unassigned shards: $shards")

  private[shardcake] def isEntityOnLocalShards(entityId: String): UIO[Boolean] =
    for {
      shards <- shardAssignments.get
      shardId = getShardId(entityId)
      pod     = shards.get(shardId)
    } yield pod.contains(address)

  def getNumberOfPods: UIO[Int] =
    shardAssignments.get.map(_.values.toSet.size)

  private[shardcake] val refreshAssignments: ZManaged[Clock, Nothing, Unit] = {
    val assignmentStream =
      ZStream.fromEffect(
        shardManager.getAssignments.map(_ -> true) // first, get the assignments from the shard manager directly
      ) ++
        storage.assignmentsStream.map(_ -> false) // then, get assignments changes from Redis
    assignmentStream.mapM { case (assignmentsOpt, fromShardManager) =>
      val assignments = assignmentsOpt.flatMap { case (k, v) => v.map(k -> _) }
      logger.logDebug("Received new shard assignments") *>
        (if (fromShardManager) shardAssignments.update(map => if (map.isEmpty) assignments else map)
         else
           shardAssignments.update(map =>
             // we keep self assignments (we don't override them with the new assignments
             // because only the Shard Manager is able to change assignments of the current node, via assign/unassign
             assignments.filter { case (_, pod) => pod != address } ++
               map.filter { case (_, pod) => pod == address }
           ))
    }.runDrain
  }.retry(Schedule.fixed(config.refreshAssignmentsRetryInterval))
    .interruptible
    .forkDaemon
    .toManaged(_.interrupt)
    .unit

  private[shardcake] def isShuttingDown: UIO[Boolean] =
    isShuttingDownRef.get

  private[shardcake] def sendToLocalEntity(msg: BinaryMessage): Task[Option[Array[Byte]]] =
    entityStates.get.flatMap(states =>
      ZIO
        .foreach(states.get(msg.entityType)) { state =>
          for {
            p      <- Promise.make[Throwable, Option[Array[Byte]]]
            _      <- state.binaryQueue.offer((msg, p))
            result <- p.await
          } yield result
        }
        .map(_.flatten)
    )

  private[shardcake] def initReply(id: String, promise: Promise[Throwable, Option[Any]], context: String): UIO[Unit] =
    replyPromises.update(p => ZIO.succeed(p.updated(id, promise))) <*
      promise.await
        // timeout slightly > send timeout
        .timeoutFail(new Exception(s"Promise was not completed in time. $context"))(config.sendTimeout.plusSeconds(1))
        .onError(cause => abortReply(id, cause.squash))
        .provideLayer(Clock.live)
        .forkDaemon

  private def abortReply(id: String, ex: Throwable): UIO[Unit] =
    replyPromises.update(promises => ZIO.whenCase(promises.get(id)) { case Some(p) => p.fail(ex) }.as(promises - id))

  def reply[Reply](reply: Reply, replier: Replier[Reply]): UIO[Unit] =
    replyPromises.update(promises =>
      ZIO.whenCase(promises.get(replier.id)) { case Some(p) => p.succeed(Some(reply)) }.as(promises - replier.id)
    )

  def messenger[Msg](entityType: EntityType[Msg]): Messenger[Msg] =
    new Messenger[Msg] {
      def sendDiscard(entityId: String)(msg: Msg): UIO[Unit] =
        sendMessage(entityId, msg, None).timeout(config.sendTimeout).provideLayer(Clock.live).forkDaemon.unit

      def send[Res](entityId: String)(msg: Replier[Res] => Msg): Task[Res] =
        random.nextUUID.flatMap { uuid =>
          val body = msg(Replier(uuid.toString))
          sendMessage[Res](entityId, body, Some(uuid.toString)).flatMap {
            case Some(value) => ZIO.succeed(value)
            case None        => ZIO.fail(new Exception(s"Send returned nothing, entityId=$entityId, body=$body"))
          }
            .timeoutFail(SendTimeoutException(entityType, entityId, body))(config.sendTimeout)
            .provideLayer(Clock.live)
            .interruptible
        }

      private def sendMessage[Res](entityId: String, msg: Msg, replyId: Option[String]): Task[Option[Res]] = {
        val shardId = getShardId(entityId)

        def trySend: Task[Option[Res]] =
          for {
            shards   <- shardAssignments.get
            pod       = shards.get(shardId)
            response <- pod match {
                          case Some(pod) =>
                            val send =
                              if (pod == address) {
                                // if pod = self, shortcut and send directly without serialization
                                Promise
                                  .make[Throwable, Option[Any]]
                                  .flatMap(p =>
                                    entityStates.get.flatMap(s =>
                                      ZIO
                                        .foreach(s.get(entityType.name))(
                                          _.entityManager
                                            .asInstanceOf[EntityManager[Msg]]
                                            .send(entityId, msg, replyId, p) *>
                                            p.await.map(_.asInstanceOf[Option[Res]])
                                        )
                                        .map(_.flatten)
                                    )
                                  )
                              } else {
                                serialization
                                  .encode(msg)
                                  .flatMap(bytes =>
                                    pods
                                      .sendMessage(pod, BinaryMessage(entityId, entityType.name, bytes, replyId))
                                      .tapError {
                                        ZIO.whenCase(_) { case PodUnavailable(pod) =>
                                          val notify = clock.currentDateTime.flatMap(cdt =>
                                            lastUnhealthyNodeReported
                                              .updateAndGet(old =>
                                                if (
                                                  old.plusNanos(config.unhealthyPodReportInterval.toNanos) isBefore cdt
                                                )
                                                  cdt
                                                else old
                                              )
                                              .map(_ isEqual cdt)
                                          )
                                          ZIO.whenM(notify)(shardManager.notifyUnhealthyPod(pod).forkDaemon)
                                        }
                                      }
                                      .flatMap(ZIO.foreach(_)(serialization.decode[Res]))
                                  )
                              }
                            send.catchSome { case _: EntityNotManagedByThisPod | _: PodUnavailable =>
                              clock.sleep(200.millis) *> trySend
                            }
                          case None      =>
                            // no shard assignment, retry
                            clock.sleep(100.millis) *> trySend
                        }
          } yield response

        trySend
      }
    }

  def registerEntity[R, Req: Tag](
    entityType: EntityType[Req],
    behavior: (String, Dequeue[Req]) => RIO[R, Nothing],
    terminateMessage: Promise[Nothing, Unit] => Option[Req] = (_: Promise[Nothing, Unit]) => None
  ): ZManaged[Clock with R, Nothing, Unit] =
    for {
      entityManager <- EntityManager.make(behavior, terminateMessage, self, config).toManaged_
      binaryQueue   <- Queue.unbounded[(BinaryMessage, Promise[Throwable, Option[Array[Byte]]])].toManaged(_.shutdown)
      _             <- entityStates.update(_.updated(entityType.name, EntityState(binaryQueue, entityManager))).toManaged_
      _             <- ZStream
                         .fromQueue(binaryQueue)
                         .mapM { case (msg, p) =>
                           serialization
                             .decode[Req](msg.body)
                             .map(req => Some((req, msg.entityId, p, msg.replyId)))
                             .catchAll(p.fail(_).as(None))
                         }
                         .collectSome
                         .foreach { case (msg, entityId, p, replyId) =>
                           Promise
                             .make[Throwable, Option[Any]]
                             .flatMap(p2 =>
                               entityManager.send(entityId, msg, replyId, p2).catchAll(p.fail) *>
                                 p2.await.flatMap(
                                   ZIO.foreach(_)(serialization.encode).flatMap(p.succeed(_)).catchAll(p.fail(_)).fork
                                 )
                             )
                         }
                         .forkManaged
    } yield ()
}

object Sharding {
  private[shardcake] case class EntityState(
    binaryQueue: Queue[(BinaryMessage, Promise[Throwable, Option[Array[Byte]]])],
    entityManager: EntityManager[Nothing]
  )

  /**
   * A layer that sets up sharding communication between pods.
   */
  val live: ZLayer[Has[Pods] with Has[ShardManagerClient] with Has[Storage] with Has[Config] with Has[
    Logging
  ] with Has[Serialization] with Clock with Random, Throwable, Has[Sharding]] = (
    for {
      config                    <- ZManaged.service[Config]
      pods                      <- ZManaged.service[Pods]
      shardManager              <- ZManaged.service[ShardManagerClient]
      storage                   <- ZManaged.service[Storage]
      logger                    <- ZManaged.service[Logging]
      clock                     <- ZManaged.service[Clock.Service]
      random                    <- ZManaged.service[Random.Service]
      serialization             <- ZManaged.service[Serialization]
      shardsCache               <- Ref.make(Map.empty[ShardId, PodAddress]).toManaged_
      entityStates              <- Ref.make[Map[String, EntityState]](Map()).toManaged_
      singletons                <- RefM
                                     .make[List[(String, UIO[Nothing], Option[Fiber[Nothing, Nothing]])]](Nil)
                                     .toManaged(
                                       _.get.flatMap(singletons =>
                                         ZIO.foreach(singletons) {
                                           case (_, _, Some(fiber)) => fiber.interrupt
                                           case _                   => ZIO.unit
                                         }
                                       )
                                     )
      promises                  <- RefM.make[Map[String, Promise[Throwable, Option[Any]]]](Map()).toManaged_
      cdt                       <- clock.currentDateTime.toManaged_
      lastUnhealthyNodeReported <- Ref.make(cdt).toManaged_
      shuttingDown              <- Ref.make(false).toManaged_
      sharding                   = new Sharding(
                                     PodAddress(config.selfHost, config.shardingPort),
                                     config,
                                     shardsCache,
                                     entityStates,
                                     singletons,
                                     promises,
                                     lastUnhealthyNodeReported,
                                     shuttingDown,
                                     shardManager,
                                     pods,
                                     storage,
                                     logger,
                                     random,
                                     clock,
                                     serialization
                                   )
      _                         <- sharding.refreshAssignments
    } yield sharding
  ).toLayer

  /**
   * Notify the shard manager that shards can now be assigned to this pod.
   */
  def register: RIO[Has[Sharding], Unit] =
    ZIO.serviceWith[Sharding](_.register)

  /**
   * Notify the shard manager that shards must be unassigned from this pod.
   */
  def unregister: RIO[Has[Sharding], Unit] =
    ZIO.serviceWith[Sharding](_.unregister)

  /**
   * Same as `register`, but will automatically call `unregister` when the `Scope` is terminated.
   */
  def registerManaged: RManaged[Has[Sharding], Unit] =
    Sharding.register.toManaged(_ => Sharding.unregister.ignore)

  /**
   * Start a computation that is guaranteed to run only on a single pod.
   * Each pod should call `registerSingleton` but only a single pod will actually run it at any given time.
   */
  def registerSingleton(name: String, run: UIO[Nothing]): URIO[Has[Sharding], Unit] =
    ZIO.serviceWith[Sharding](_.registerSingleton(name, run))

  /**
   * Register a new entity type, allowing pods to send messages to entities of this type.
   * It takes a `behavior` which is a function from an entity ID and a queue of messages to a ZIO computation that runs forever and consumes those messages.
   * You can use `ZIO.interrupt` from the behavior to stop it (it will be restarted the next time the entity receives a message).
   * If provided, the optional `terminateMessage` will be sent to the entity before it is stopped, allowing for cleanup logic.
   */
  def registerEntity[R, Req: Tag](
    entityType: EntityType[Req],
    behavior: (String, Dequeue[Req]) => RIO[R, Nothing],
    terminateMessage: Promise[Nothing, Unit] => Option[Req] = (_: Promise[Nothing, Unit]) => None
  ): ZManaged[Has[Sharding] with R with Clock, Nothing, Unit] =
    for {
      sharding <- ZIO.service[Sharding].toManaged_
      _        <- sharding.registerEntity[R, Req](entityType, behavior, terminateMessage)
    } yield ()

  /**
   * Get an object that allows sending messages to a given entity type.
   */
  def messenger[Msg](entityType: EntityType[Msg]): URIO[Has[Sharding], Messenger[Msg]] =
    ZIO.service[Sharding].map(_.messenger(entityType))

  /**
   * Get the number of pods currently registered to the Shard Manager
   */
  def getNumberOfPods: RIO[Has[Sharding], Int] =
    ZIO.serviceWith[Sharding](_.getNumberOfPods)
}
