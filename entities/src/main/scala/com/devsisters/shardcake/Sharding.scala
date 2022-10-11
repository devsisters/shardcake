package com.devsisters.shardcake

import com.devsisters.shardcake.Messenger.Replier
import com.devsisters.shardcake.Sharding.EntityState
import com.devsisters.shardcake.errors.{ EntityNotManagedByThisPod, PodUnavailable, SendTimeoutException }
import com.devsisters.shardcake.interfaces.Pods.BinaryMessage
import com.devsisters.shardcake.interfaces.{ Pods, Serialization, Storage }
import com.devsisters.shardcake.internal.EntityManager
import zio._
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
  singletons: Ref.Synchronized[List[(String, UIO[Nothing], Option[Fiber[Nothing, Nothing]])]],
  replyPromises: Ref.Synchronized[Map[String, Promise[Throwable, Option[Any]]]], // promise for each pending reply,
  lastUnhealthyNodeReported: Ref[OffsetDateTime],
  isShuttingDownRef: Ref[Boolean],
  shardManager: ShardManagerClient,
  pods: Pods,
  storage: Storage,
  serialization: Serialization
) { self =>
  private[shardcake] def getShardId(entityId: String): ShardId =
    math.abs(entityId.hashCode % config.numberOfShards) + 1

  val register: Task[Unit] =
    ZIO.logDebug(s"Registering pod $address to Shard Manager") *>
      isShuttingDownRef.set(false) *>
      shardManager.register(address)

  val unregister: Task[Unit] =
    shardManager.getAssignments *> // ping the shard manager first to stop if it's not available
      ZIO.logDebug(s"Stopping local entities") *>
      isShuttingDownRef.set(true) *>
      entityStates.get.flatMap(states => ZIO.foreachDiscard(states.values)(_.entityManager.terminateAllEntities)) *>
      ZIO.logDebug(s"Unregistering pod $address to Shard Manager") *>
      shardManager.unregister(address)

  private def isSingletonNode: UIO[Boolean] =
    // Start singletons on the pod hosting shard 1.
    shardAssignments.get.map(_.get(1).contains(address))

  private def startSingletonsIfNeeded: UIO[Unit] =
    ZIO
      .whenZIO(isSingletonNode) {
        singletons.updateZIO { singletons =>
          ZIO.foreach(singletons) {
            case (name, run, None) =>
              ZIO.logDebug(s"Starting singleton $name") *> run.forkDaemon.map(fiber => (name, run, Some(fiber)))
            case other             => ZIO.succeed(other)
          }
        }
      }
      .unit

  private def stopSingletonsIfNeeded: UIO[Unit] =
    ZIO
      .unlessZIO(isSingletonNode) {
        singletons.updateZIO { singletons =>
          ZIO.foreach(singletons) {
            case (name, run, Some(fiber)) =>
              ZIO.logDebug(s"Stopping singleton $name") *> fiber.interrupt.as((name, run, None))
            case other                    => ZIO.succeed(other)
          }
        }
      }
      .unit

  def registerSingleton(name: String, run: UIO[Nothing]): UIO[Unit] =
    singletons.update(list => (name, run, None) :: list) *> startSingletonsIfNeeded

  private[shardcake] def assign(shards: Set[ShardId]): UIO[Unit] =
    ZIO
      .unlessZIO(isShuttingDown) {
        shardAssignments.update(shards.foldLeft(_) { case (map, shard) => map.updated(shard, address) }) *>
          startSingletonsIfNeeded <*
          ZIO.logDebug(s"Assigned shards: $shards")
      }
      .unit

  private[shardcake] def unassign(shards: Set[ShardId]): UIO[Unit] =
    shardAssignments.update(shards.foldLeft(_) { case (map, shard) =>
      if (map.get(shard).contains(address)) map - shard else map
    }) *>
      ZIO.logDebug(s"Unassigning shards: $shards") *>
      entityStates.get.flatMap(state =>
        ZIO.foreachDiscard(state.values)(
          _.entityManager.terminateEntitiesOnShards(shards) // this will return once all shards are terminated
        )
      ) *>
      stopSingletonsIfNeeded <*
      ZIO.logDebug(s"Unassigned shards: $shards")

  private[shardcake] def isEntityOnLocalShards(entityId: String): UIO[Boolean] =
    for {
      shards <- shardAssignments.get
      shardId = getShardId(entityId)
      pod     = shards.get(shardId)
    } yield pod.contains(address)

  def getPods: UIO[Set[PodAddress]] =
    shardAssignments.get.map(_.values.toSet)

  private def updateAssignments(
    assignmentsOpt: Map[ShardId, Option[PodAddress]],
    fromShardManager: Boolean
  ): UIO[Unit] = {
    val assignments = assignmentsOpt.flatMap { case (k, v) => v.map(k -> _) }
    ZIO.logDebug("Received new shard assignments") *>
      (if (fromShardManager) shardAssignments.update(map => if (map.isEmpty) assignments else map)
       else
         shardAssignments.update(map =>
           // we keep self assignments (we don't override them with the new assignments
           // because only the Shard Manager is able to change assignments of the current node, via assign/unassign
           assignments.filter { case (_, pod) => pod != address } ++
             map.filter { case (_, pod) => pod == address }
         ))
  }

  private[shardcake] val refreshAssignments: ZIO[Scope, Nothing, Unit] = {
    val assignmentStream =
      ZStream.fromZIO(
        shardManager.getAssignments.map(_ -> true) // first, get the assignments from the shard manager directly
      ) ++
        storage.assignmentsStream.map(_ -> false) // then, get assignments changes from Redis
    assignmentStream.mapZIO { case (assignmentsOpt, fromShardManager) =>
      updateAssignments(assignmentsOpt, fromShardManager)
    }.runDrain
  }.retry(Schedule.fixed(config.refreshAssignmentsRetryInterval))
    .interruptible
    .forkDaemon
    .withFinalizer(_.interrupt)
    .unit

  private[shardcake] def isShuttingDown: UIO[Boolean] =
    isShuttingDownRef.get

  private[shardcake] def sendToLocalEntity(msg: BinaryMessage): Task[Option[Array[Byte]]] =
    entityStates.get.flatMap(states =>
      states.get(msg.entityType) match {
        case Some(state) =>
          for {
            p      <- Promise.make[Throwable, Option[Array[Byte]]]
            _      <- state.binaryQueue.offer((msg, p))
            result <- p.await
          } yield result

        case None => ZIO.fail(new Exception(s"No such entity type found: ${msg.entityType}"))
      }
    )

  private[shardcake] def initReply(id: String, promise: Promise[Throwable, Option[Any]], context: String): UIO[Unit] =
    replyPromises.update(_.updated(id, promise)) <*
      promise.await
        // timeout slightly > send timeout
        .timeoutFail(new Exception(s"Promise was not completed in time. $context"))(config.sendTimeout.plusSeconds(1))
        .onError(cause => abortReply(id, cause.squash))
        .forkDaemon

  private def abortReply(id: String, ex: Throwable): UIO[Unit] =
    replyPromises.updateZIO(promises => ZIO.whenCase(promises.get(id)) { case Some(p) => p.fail(ex) }.as(promises - id))

  def reply[Reply](reply: Reply, replier: Replier[Reply]): UIO[Unit] =
    replyPromises.updateZIO(promises =>
      ZIO.whenCase(promises.get(replier.id)) { case Some(p) => p.succeed(Some(reply)) }.as(promises - replier.id)
    )

  def messenger[Msg](entityType: EntityType[Msg]): Messenger[Msg] =
    new Messenger[Msg] {
      def sendDiscard(entityId: String)(msg: Msg): UIO[Unit] =
        sendMessage(entityId, msg, None).timeout(config.sendTimeout).forkDaemon.unit

      def send[Res](entityId: String)(msg: Replier[Res] => Msg): Task[Res] =
        Random.nextUUID.flatMap { uuid =>
          val body = msg(Replier(uuid.toString))
          sendMessage[Res](entityId, body, Some(uuid.toString)).flatMap {
            case Some(value) => ZIO.succeed(value)
            case None        => ZIO.fail(new Exception(s"Send returned nothing, entityId=$entityId, body=$body"))
          }
            .timeoutFail(SendTimeoutException(entityType, entityId, body))(config.sendTimeout)
            .interruptible
        }

      private def sendMessage[Res](entityId: String, msg: Msg, replyId: Option[String]): Task[Option[Res]] = {
        val shardId                    = getShardId(entityId)
        def trySend: Task[Option[Res]] =
          for {
            shards   <- shardAssignments.get
            pod       = shards.get(shardId)
            response <- pod match {
                          case Some(pod) =>
                            val send = {
                              if (config.simulateRemotePods && pod == address) {
                                serialization
                                  .encode(msg)
                                  .flatMap(bytes =>
                                    sendToLocalEntity(BinaryMessage(entityId, entityType.name, bytes, replyId))
                                      .flatMap(ZIO.foreach(_)(serialization.decode[Res]))
                                  )
                              } else if (pod == address) {
                                // if pod = self, shortcut and send directly without serialization
                                Promise
                                  .make[Throwable, Option[Any]]
                                  .flatMap(p =>
                                    entityStates.get.flatMap(
                                      _.get(entityType.name) match {
                                        case Some(state) =>
                                          state.entityManager
                                            .asInstanceOf[EntityManager[Msg]]
                                            .send(entityId, msg, replyId, p) *>
                                            p.await.map(_.asInstanceOf[Option[Res]])

                                        case None =>
                                          ZIO.fail(new Exception(s"No such entity type found: ${entityType.name}"))
                                      }
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
                                          val notify = Clock.currentDateTime.flatMap(cdt =>
                                            lastUnhealthyNodeReported
                                              .updateAndGet(old =>
                                                if (
                                                  old.plusNanos(config.unhealthyPodReportInterval.toNanos) isBefore cdt
                                                ) cdt
                                                else old
                                              )
                                              .map(_ isEqual cdt)
                                          )
                                          ZIO.whenZIO(notify)(
                                            (shardManager.notifyUnhealthyPod(pod) *>
                                              // just in case we missed the update from the pubsub, refresh assignments
                                              shardManager.getAssignments
                                                .flatMap(updateAssignments(_, fromShardManager = true))).forkDaemon
                                          )
                                        }
                                      }
                                      .flatMap(ZIO.foreach(_)(serialization.decode[Res]))
                                  )
                              }
                            }
                            send.catchSome { case _: EntityNotManagedByThisPod | _: PodUnavailable =>
                              Clock.sleep(200.millis) *> trySend
                            }
                          case None      =>
                            // no shard assignment, retry
                            Clock.sleep(100.millis) *> trySend
                        }
          } yield response

        trySend
      }
    }

  def registerEntity[R, Req: Tag](
    entityType: EntityType[Req],
    behavior: (String, Dequeue[Req]) => RIO[R, Nothing],
    terminateMessage: Promise[Nothing, Unit] => Option[Req] = (_: Promise[Nothing, Unit]) => None
  ): URIO[Scope with R, Unit] =
    for {
      entityManager <- EntityManager.make(behavior, terminateMessage, self, config)
      binaryQueue   <- Queue.unbounded[(BinaryMessage, Promise[Throwable, Option[Array[Byte]]])].withFinalizer(_.shutdown)
      _             <- entityStates.update(_.updated(entityType.name, EntityState(binaryQueue, entityManager)))
      _             <- ZStream
                         .fromQueue(binaryQueue)
                         .mapZIO { case (msg, p) =>
                           (for {
                             req       <- serialization.decode[Req](msg.body)
                             p2        <- Promise.make[Throwable, Option[Any]]
                             _         <- entityManager.send(msg.entityId, req, msg.replyId, p2)
                             resOption <- p2.await
                             res       <- ZIO.foreach(resOption)(serialization.encode)
                             _         <- p.succeed(res)
                           } yield ())
                             .catchAllCause((cause: Cause[Throwable]) => p.fail(cause.squash))
                             .fork
                             .unit
                         }
                         .runDrain
                         .forkScoped
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
  val live: ZLayer[Pods with ShardManagerClient with Storage with Serialization with Config, Throwable, Sharding] =
    ZLayer.scoped {
      for {
        config                    <- ZIO.service[Config]
        pods                      <- ZIO.service[Pods]
        shardManager              <- ZIO.service[ShardManagerClient]
        storage                   <- ZIO.service[Storage]
        serialization             <- ZIO.service[Serialization]
        shardsCache               <- Ref.make(Map.empty[ShardId, PodAddress])
        entityStates              <- Ref.make[Map[String, EntityState]](Map())
        singletons                <- Ref.Synchronized
                                       .make[List[(String, UIO[Nothing], Option[Fiber[Nothing, Nothing]])]](Nil)
                                       .withFinalizer(
                                         _.get.flatMap(singletons =>
                                           ZIO.foreach(singletons) {
                                             case (_, _, Some(fiber)) => fiber.interrupt
                                             case _                   => ZIO.unit
                                           }
                                         )
                                       )
        promises                  <- Ref.Synchronized.make[Map[String, Promise[Throwable, Option[Any]]]](Map())
        cdt                       <- Clock.currentDateTime
        lastUnhealthyNodeReported <- Ref.make(cdt)
        shuttingDown              <- Ref.make(false)
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
                                       serialization
                                     )
        _                         <- sharding.refreshAssignments
      } yield sharding
    }

  /**
   * Notify the shard manager that shards can now be assigned to this pod.
   */
  def register: RIO[Sharding, Unit] =
    ZIO.serviceWithZIO[Sharding](_.register)

  /**
   * Notify the shard manager that shards must be unassigned from this pod.
   */
  def unregister: RIO[Sharding, Unit] =
    ZIO.serviceWithZIO[Sharding](_.unregister)

  /**
   * Same as `register`, but will automatically call `unregister` when the `Scope` is terminated.
   */
  def registerScoped: RIO[Sharding with Scope, Unit] =
    Sharding.register.withFinalizer(_ => Sharding.unregister.ignore)

  /**
   * Start a computation that is guaranteed to run only on a single pod.
   * Each pod should call `registerSingleton` but only a single pod will actually run it at any given time.
   */
  def registerSingleton(name: String, run: UIO[Nothing]): URIO[Sharding, Unit] =
    ZIO.serviceWithZIO[Sharding](_.registerSingleton(name, run))

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
  ): URIO[Sharding with Scope with R, Unit] =
    ZIO.serviceWithZIO[Sharding](_.registerEntity[R, Req](entityType, behavior, terminateMessage))

  /**
   * Get an object that allows sending messages to a given entity type.
   */
  def messenger[Msg](entityType: EntityType[Msg]): URIO[Sharding, Messenger[Msg]] =
    ZIO.serviceWith[Sharding](_.messenger(entityType))

  /**
   * Get the list of pods currently registered to the Shard Manager
   */
  def getPods: RIO[Sharding, Set[PodAddress]] =
    ZIO.serviceWithZIO[Sharding](_.getPods)
}
