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

class Sharding(
  address: PodAddress,
  numberOfShards: Int,
  shardAssignments: Ref[Map[ShardId, PodAddress]],
  singletons: Ref.Synchronized[List[(String, UIO[Nothing], Option[Fiber[Nothing, Nothing]])]],
  entityStates: Ref.Synchronized[Map[String, EntityState]],
  promises: Ref.Synchronized[Map[String, Promise[Throwable, Option[Any]]]], // promise for each pending reply,
  lastUnhealthyNodeReported: Ref[OffsetDateTime],
  isShuttingDownRef: Ref[Boolean],
  shardManager: ShardManagerClient,
  pods: Pods,
  storage: Storage
) { self =>
  private[shardcake] def getShardId(entityId: String): ShardId =
    math.abs(entityId.hashCode % numberOfShards) + 1

  val register: Task[Unit] =
    ZIO.logDebug(s"Registering pod $address to Shard Manager") *>
      shardManager.register(address)

  val unregister: Task[Unit] =
    ZIO.logDebug(s"Stopping local entities") *>
      isShuttingDownRef.set(true) *>
      entityStates.get.flatMap(states => ZIO.foreachDiscard(states.values)(_.entityManager.terminateAllEntities)) *>
      ZIO.logDebug(s"Unregistering pod $address to Shard Manager") *>
      shardManager.unregister(address)

  private def isSingletonNode: UIO[Boolean] =
    // In theory, it would be better to start a single instance in kubernetes with a new role that is just for our singletons
    // and use shard manager to communicate with other nodes.
    // But since our singletons are so tiny, that is a little overkill,
    // so we just start them on the pod hosting shard 1.
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

  def getNumberOfPods: UIO[Int] =
    shardAssignments.get.map(_.values.toSet.size)

  private val refreshAssignments: ZIO[Scope, Nothing, Unit] = {
    val assignmentStream =
      ZStream.fromZIO(
        shardManager.getAssignments.map(_ -> true) // first, get the assignments from the shard manager directly
      ) ++
        storage.assignmentsStream.map(_ -> false) // then, get assignments changes from Redis
    assignmentStream.mapZIO { case (assignmentsOpt, fromShardManager) =>
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
    }.runDrain
  }.retry(Schedule.fixed(5 seconds)).interruptible.forkDaemon.withFinalizer(_.interrupt).unit

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
    promises.update(_.updated(id, promise)) <*
      promise.await
        .timeoutFail(new Exception(s"Promise was not completed in time. $context"))(12 seconds) // > send timeout
        .onError(cause => abortReply(id, cause.squash))
        .forkDaemon

  private def abortReply(id: String, ex: Throwable): UIO[Unit] =
    promises.updateZIO(promises => ZIO.whenCase(promises.get(id)) { case Some(p) => p.fail(ex) }.as(promises - id))

  def reply[Reply](reply: Reply, replyTo: Replier[Reply]): UIO[Unit] =
    promises.updateZIO(promises =>
      ZIO.whenCase(promises.get(replyTo.id)) { case Some(p) => p.succeed(Some(reply)) }.as(promises - replyTo.id)
    )

  def registerEntity[R, Req: Tag](
    entityType: EntityType[Req],
    behavior: (String, Dequeue[Req]) => RIO[R, Nothing],
    terminateMessage: Promise[Nothing, Unit] => Option[Req] = (_: Promise[Nothing, Unit]) => None
  ): URIO[Scope with Serialization with R, Messenger[Req]] =
    for {
      serialization <- ZIO.service[Serialization]
      entityManager <- EntityManager.make(behavior, terminateMessage, self)
      binaryQueue   <- Queue.unbounded[(BinaryMessage, Promise[Throwable, Option[Array[Byte]]])].withFinalizer(_.shutdown)
      _             <- entityStates.update(_.updated(entityType.value, EntityState(binaryQueue, entityManager)))
      _             <- ZStream
                         .fromQueue(binaryQueue)
                         .mapZIO { case (msg, p) =>
                           serialization
                             .decode[Req](msg.body)
                             .map(req => Some((req, msg.entityId, p, msg.replyId)))
                             .catchAll(p.fail(_).as(None))
                         }
                         .collectSome
                         .runForeach { case (msg, entityId, p, replyId) =>
                           Promise
                             .make[Throwable, Option[Any]]
                             .flatMap(p2 =>
                               entityManager.send(entityId, msg, replyId, p2).catchAll(p.fail) *>
                                 p2.await.flatMap(
                                   ZIO.foreach(_)(serialization.encode).flatMap(p.succeed(_)).catchAll(p.fail(_)).fork
                                 )
                             )
                         }
                         .forkScoped
    } yield new Messenger[Req] {

      def sendDiscard(entityId: String)(msg: Req): UIO[Unit] =
        sendMessage(entityId, msg, None).timeout(10 seconds).forkDaemon.unit

      def send[Res](entityId: String)(msg: Replier[Res] => Req): Task[Res] =
        Random.nextUUID.flatMap { uuid =>
          val body = msg(Replier(uuid.toString))
          sendMessage[Res](entityId, body, Some(uuid.toString)).flatMap {
            case Some(value) => ZIO.succeed(value)
            case None        => ZIO.fail(new Exception(s"Send returned nothing, entityId=$entityId, body=$body"))
          }
            .timeoutFail(SendTimeoutException(entityType, entityId, body))(10 seconds)
            .interruptible
        }

      private def sendMessage[Res](entityId: String, msg: Req, replyId: Option[String]): Task[Option[Res]] = {
        val shardId                    = getShardId(entityId)
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
                                    entityManager.send(entityId, msg, replyId, p) *>
                                      p.await.map(_.asInstanceOf[Option[Res]])
                                  )
                              } else {
                                serialization
                                  .encode(msg)
                                  .flatMap(bytes =>
                                    pods
                                      .sendMessage(pod, BinaryMessage(entityId, entityType.value, bytes, replyId))
                                      .tapError {
                                        ZIO.whenCase(_) { case PodUnavailable(pod) =>
                                          val notify = Clock.currentDateTime.flatMap(cdt =>
                                            lastUnhealthyNodeReported
                                              .updateAndGet(old => if (old.plusSeconds(5) isBefore cdt) cdt else old)
                                              .map(_ isEqual cdt)
                                          )
                                          ZIO.whenZIO(notify)(shardManager.notifyUnhealthyPod(pod).forkDaemon)
                                        }
                                      }
                                      .flatMap(ZIO.foreach(_)(serialization.decode[Res]))
                                  )
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
}

object Sharding {
  val live: ZLayer[Pods with ShardManagerClient with Storage with Config, Throwable, Sharding] =
    ZLayer.scoped {
      for {
        config                    <- ZIO.service[Config]
        pods                      <- ZIO.service[Pods]
        shardManager              <- ZIO.service[ShardManagerClient]
        storage                   <- ZIO.service[Storage]
        shardsCache               <- Ref.make(Map.empty[ShardId, PodAddress])
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
        entityStates              <- Ref.Synchronized.make[Map[String, EntityState]](Map())
        promises                  <- Ref.Synchronized.make[Map[String, Promise[Throwable, Option[Any]]]](Map())
        cdt                       <- Clock.currentDateTime
        lastUnhealthyNodeReported <- Ref.make(cdt)
        shuttingDown              <- Ref.make(false)
        sharding                   = new Sharding(
                                       PodAddress(config.selfHost, config.shardingPort),
                                       config.numberOfShards,
                                       shardsCache,
                                       singletons,
                                       entityStates,
                                       promises,
                                       lastUnhealthyNodeReported,
                                       shuttingDown,
                                       shardManager,
                                       pods,
                                       storage
                                     )
        _                         <- sharding.refreshAssignments
      } yield sharding
    }

  def register: RIO[Sharding, Unit] =
    ZIO.serviceWithZIO[Sharding](_.register)

  def unregister: RIO[Sharding, Unit] =
    ZIO.serviceWithZIO[Sharding](_.unregister)

  def registerScoped: RIO[Sharding with Scope, Unit] =
    Sharding.register.withFinalizer(_ => Sharding.unregister.ignore)

  case class EntityState(
    binaryQueue: Queue[(BinaryMessage, Promise[Throwable, Option[Array[Byte]]])],
    entityManager: EntityManager[Nothing]
  )
}
