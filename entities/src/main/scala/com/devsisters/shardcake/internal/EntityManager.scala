package com.devsisters.shardcake.internal

import com.devsisters.shardcake.errors.EntityNotManagedByThisPod
import com.devsisters.shardcake.{ Config, ShardId, Sharding }
import zio._

private[shardcake] trait EntityManager[-Req] {
  def send(
    entityId: String,
    req: Req,
    replyId: Option[String],
    promise: Promise[Throwable, Option[Any]]
  ): IO[EntityNotManagedByThisPod, Unit]
  def terminateEntitiesOnShards(shards: Set[ShardId]): UIO[Unit]
  def terminateAllEntities: UIO[Unit]
}

private[shardcake] object EntityManager {
  def make[R, Req: Tag](
    behavior: (String, Queue[Req]) => RIO[R, Nothing],
    terminateMessage: Promise[Nothing, Unit] => Option[Req],
    sharding: Sharding,
    config: Config
  ): URIO[R, EntityManager[Req]] =
    for {
      entities <- Ref.Synchronized.make[Map[String, (Option[Queue[Req]], Fiber[Nothing, Unit])]](Map())
      env      <- ZIO.environment[R]
    } yield new EntityManagerLive[Req](
      (entityId: String, queue: Queue[Req]) => behavior(entityId, queue).provideEnvironment(env),
      terminateMessage,
      entities,
      sharding,
      config
    )

  class EntityManagerLive[Req](
    behavior: (String, Queue[Req]) => Task[Nothing],
    terminateMessage: Promise[Nothing, Unit] => Option[Req],
    entities: Ref.Synchronized[Map[String, (Option[Queue[Req]], Fiber[Nothing, Unit])]],
    sharding: Sharding,
    config: Config
  ) extends EntityManager[Req] {
    private def startExpirationFiber(entityId: String): UIO[Fiber[Nothing, Unit]] =
      (for {
        _ <- Clock.sleep(config.entityMaxIdleTime)
        _ <- terminateEntity(entityId).forkDaemon.unit // fork daemon otherwise it will interrupt itself
      } yield ()).forkDaemon

    private def terminateEntity(entityId: String): UIO[Unit] =
      entities.updateZIO(map =>
        map.get(entityId) match {
          case Some((Some(queue), interruptionFiber)) =>
            Promise
              .make[Nothing, Unit]
              .flatMap { p =>
                terminateMessage(p) match {
                  case Some(msg) =>
                    // if a queue is found, offer the termination message, and set the queue to None so that no new message is enqueued
                    queue.offer(msg).exit.as(map.updated(entityId, (None, interruptionFiber)))
                  case None      =>
                    queue.shutdown.as(map - entityId)
                }
              }
          case _                                      =>
            // if no queue is found, do nothing
            ZIO.succeed(map)
        }
      )

    def send(
      entityId: String,
      req: Req,
      replyId: Option[String],
      promise: Promise[Throwable, Option[Any]]
    ): IO[EntityNotManagedByThisPod, Unit] =
      for {
        // first, verify that this entity should be handled by this pod
        _     <- ZIO.unlessZIO(sharding.isEntityOnLocalShards(entityId))(ZIO.fail(EntityNotManagedByThisPod(entityId)))
        // find the queue for that entity, or create it if needed
        queue <- entities.modifyZIO(map =>
                   map.get(entityId) match {
                     case Some((queue @ Some(_), expirationFiber)) =>
                       // queue exists, delay the interruption fiber and return the queue
                       expirationFiber.interrupt *>
                         startExpirationFiber(entityId).map(fiber => (queue, map.updated(entityId, (queue, fiber))))
                     case Some((None, _))                          =>
                       // the queue is shutting down, stash and retry
                       ZIO.succeed((None, map))
                     case None                                     =>
                       sharding.isShuttingDown.flatMap {
                         case true  =>
                           // don't start any fiber while sharding is shutting down
                           ZIO.fail(EntityNotManagedByThisPod(entityId))
                         case false =>
                           // queue doesn't exist, create a new one
                           for {
                             queue           <- Queue.unbounded[Req]
                             // start the expiration fiber
                             expirationFiber <- startExpirationFiber(entityId)
                             _               <- behavior(entityId, queue)
                                                  .ensuring(
                                                    // shutdown the queue when the fiber ends
                                                    entities.update(_ - entityId) *> queue.shutdown *> expirationFiber.interrupt
                                                  )
                                                  .forkDaemon
                             someQueue        = Some(queue)
                           } yield (someQueue, map.updated(entityId, (someQueue, expirationFiber)))
                       }
                   }
                 )
        _     <- queue match {
                   case None        =>
                     // the queue is shutting down, try again a little later
                     Clock.sleep(100 millis) *> send(entityId, req, replyId, promise)
                   case Some(queue) =>
                     // add the message to the queue and setup the response promise if needed
                     (replyId match {
                       case Some(replyId) =>
                         sharding.initReply(replyId, promise, s"entityId: $entityId, req: $req") *> queue.offer(req)
                       case None          => queue.offer(req) *> promise.succeed(None)
                     }).catchAllCause(_ => send(entityId, req, replyId, promise))
                 }
      } yield ()

    def terminateEntitiesOnShards(shards: Set[ShardId]): UIO[Unit] =
      entities.modify { entities =>
        // get all entities on the given shards to terminate them
        entities.partition { case (entityId, _) => shards.contains(sharding.getShardId(entityId)) }
      }
        .flatMap(terminateEntities)

    def terminateAllEntities: UIO[Unit] =
      entities.getAndSet(Map()).flatMap(terminateEntities)

    private def terminateEntities(
      entitiesToTerminate: Map[String, (Option[Queue[Req]], Fiber[Nothing, Unit])]
    ): UIO[Unit] =
      for {
        // send termination message to all entities
        promises <- ZIO.foreach(entitiesToTerminate.toList) { case (_, (queue, _)) =>
                      Promise
                        .make[Nothing, Unit]
                        .tap(p =>
                          queue match {
                            case Some(queue) =>
                              terminateMessage(p) match {
                                case Some(terminate) => queue.offer(terminate).catchAllCause(_ => p.succeed(()))
                                case None            => queue.shutdown *> p.succeed(())
                              }
                            case None        => p.succeed(())
                          }
                        )
                    }
        // wait until they are all terminated
        _        <- ZIO.foreachDiscard(promises)(_.await).timeout(config.entityTerminationTimeout)
      } yield ()
  }
}
