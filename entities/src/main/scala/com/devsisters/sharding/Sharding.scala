package com.devsisters.sharding

import com.devsisters.sharding.interfaces.Storage
import com.devsisters.sharding.internal.ShardHub
import zio.stream.ZStream
import zio._

class Sharding(
  address: PodAddress,
  numberOfShards: Int,
  shardAssignments: Ref[Map[ShardId, PodAddress]],
  singletons: Ref.Synchronized[List[(String, UIO[Nothing], Option[Fiber[Nothing, Nothing]])]],
  isShuttingDownRef: Ref[Boolean],
  shardHub: ShardHub,
  shardClient: ShardClient,
  storage: Storage
) { self =>
  def hub: ShardHub = shardHub

  def getShardId(entityId: String): ShardId =
    math.abs(entityId.hashCode % numberOfShards) + 1

  val register: Task[Unit] =
    ZIO.logDebug(s"Registering pod $address to Shard Manager") *>
      shardClient.register(address)

  val unregister: Task[Unit] =
    ZIO.logDebug(s"Stopping local entities") *>
      isShuttingDownRef.set(true) *>
      shardHub.shutdown *>
      ZIO.logDebug(s"Unregistering pod $address to Shard Manager") *>
      shardClient.unregister(address)

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

  def assign(shards: Set[ShardId]): UIO[Unit] =
    ZIO
      .unlessZIO(isShuttingDown) {
        shardAssignments.update(shards.foldLeft(_) { case (map, shard) => map.updated(shard, address) }) *>
          startSingletonsIfNeeded <*
          ZIO.logDebug(s"Assigned shards: $shards")
      }
      .unit

  def unassign(shards: Set[ShardId]): UIO[Unit] =
    shardAssignments.update(shards.foldLeft(_) { case (map, shard) =>
      if (map.get(shard).contains(address)) map - shard else map
    }) *>
      ZIO.logDebug(s"Unassigning shards: $shards") *>
      shardHub.terminateShards(shards) *> // this will return once all shards are terminated
      stopSingletonsIfNeeded <*
      ZIO.logDebug(s"Unassigned shards: $shards")

  def sendMessage(message: Message): Task[Option[Any]] = {
    val shardId                    = getShardId(message.entityId)
    def trySend: Task[Option[Any]] =
      for {
        shards   <- shardAssignments.get
        pod       = shards.get(shardId)
        response <- pod match {
                      case Some(pod) =>
                        val send =
                          if (pod == address) {
                            // if pod = self, shortcut and send directly without serialization
                            shardHub.sendToLocalEntity(message.entityId, message.entityType.value, message.body)
                          } else {
                            shardClient.sendMessage(message, pod)
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

  def isEntityOnLocalShards(entityId: String): UIO[Boolean] =
    for {
      shards <- shardAssignments.get
      shardId = getShardId(entityId)
      pod     = shards.get(shardId)
    } yield pod.contains(address)

  def getNumberOfPods: UIO[Int] =
    shardAssignments.get.map(_.values.toSet.size)

  val refreshAssignments: ZIO[Scope, Nothing, Unit] = {
    val assignmentStream =
      ZStream.fromZIO(
        shardClient.getAssignments.map(_ -> true)
      ) ++ // first, get the assignments from the shard manager directly
        storage.assignmentsStream.map(_ -> false) // then, get assignments changes from Redis
    assignmentStream.mapZIO { case (assignmentsOpt, fromShardManager) =>
      val assignments = assignmentsOpt.flatMap { case (k, v) => v.map(k -> _) }
      ZIO.logDebug("Received new shard assignments") *>
        (if (fromShardManager) shardAssignments.set(assignments)
         else
           shardAssignments.update(map =>
             // we keep self assignments (we don't override them with the new assignments
             // because only the Shard Manager is able to change assignments of the current node, via assign/unassign
             assignments.filter { case (_, pod) => pod != address } ++
               map.filter { case (_, pod) => pod == address }
           ))
    }.runDrain
  }.retry(Schedule.fixed(5 seconds)).interruptible.forkDaemon.withFinalizer(_.interrupt).unit

  def isShuttingDown: UIO[Boolean] =
    isShuttingDownRef.get

//  def registerEntity[Req](
//    entityType: EntityType,
//    behavior: (String, Queue[Req]) => Task[Nothing],
//    replyTo: Req => Option[String],
//    terminateMessage: Promise[Nothing, Unit] => Req
//  ) =
//    for {
//      entities <- Ref.Synchronized.make[Map[String, (Option[Queue[Req]], Fiber[Nothing, Unit])]](Map())
//      manager   = new EntityManager[Req](behavior, replyTo, terminateMessage, entities, self)
//      _        <- manager.start(entityType).forkDaemon
//    } yield new Sender[Req] {
//      def sendMessage(message: Message2[Req]): Task[Option[Any]] = {
//        val shardId                    = getShardId(message.entityId)
//        def trySend: Task[Option[Any]] =
//          for {
//            shards   <- shardAssignments.get
//            pod       = shards.get(shardId)
//            response <- pod match {
//                          case Some(pod) =>
//                            val send =
//                              if (pod == address) {
//                                // if pod = self, shortcut and send directly without serialization
//                                shardHub.sendToLocalEntity(message.entityId, message.entityType.value, message.body)
//                              } else {
//                                shardClient.sendMessage(message, pod)
//                              }
//                            send.catchSome { case _: EntityNotManagedByThisPod | _: PodUnavailable =>
//                              Clock.sleep(200.millis) *> trySend
//                            }
//                          case None      =>
//                            // no shard assignment, retry
//                            Clock.sleep(100.millis) *> trySend
//                        }
//          } yield response
//
//        trySend
//      }
//    }
}

object Sharding {
  val live: ZLayer[ShardClient with Storage with Config, Throwable, Sharding] =
    ZLayer.scoped {
      for {
        config       <- ZIO.service[Config]
        shardClient  <- ZIO.service[ShardClient]
        storage      <- ZIO.service[Storage]
        shardHub     <- ShardHub.make
        shardsCache  <- Ref.make(Map.empty[ShardId, PodAddress])
        singletons   <- Ref.Synchronized
                          .make[List[(String, UIO[Nothing], Option[Fiber[Nothing, Nothing]])]](Nil)
                          .withFinalizer(
                            _.get.flatMap(singletons =>
                              ZIO.foreach(singletons) {
                                case (_, _, Some(fiber)) => fiber.interrupt
                                case _                   => ZIO.unit
                              }
                            )
                          )
        shuttingDown <- Ref.make(false)
        sharding      = new Sharding(
                          PodAddress(config.selfHost, config.shardingPort),
                          config.numberOfShards,
                          shardsCache,
                          singletons,
                          shuttingDown,
                          shardHub,
                          shardClient,
                          storage
                        )
        _            <- sharding.refreshAssignments
      } yield sharding
    }
}
