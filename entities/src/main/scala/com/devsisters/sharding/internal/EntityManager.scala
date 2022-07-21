package com.devsisters.sharding.internal

import com.devsisters.sharding.errors.EntityNotManagedByThisPod
import com.devsisters.sharding.{ ShardId, Sharding }
import zio._

private[sharding] trait EntityManager[-Req] {
  def send(
    entityId: String,
    req: Req,
    replyId: Option[String],
    promise: Promise[Throwable, Option[Any]]
  ): IO[EntityNotManagedByThisPod, Unit]
  def terminateEntitiesOnShards(shards: Set[ShardId]): UIO[Unit]
  def terminateAllEntities: UIO[Unit]
}

private[sharding] object EntityManager {
  def make[Req: Tag](
    behavior: (String, Queue[Req]) => Task[Nothing],
    terminateMessage: Promise[Nothing, Unit] => Option[Req],
    sharding: Sharding
  ): UIO[EntityManager[Req]] =
    for {
      entities <- Ref.Synchronized.make[Map[String, (Option[Queue[Req]], Fiber[Nothing, Unit])]](Map())
    } yield new EntityManagerLive[Req](behavior, terminateMessage, entities, sharding)

  class EntityManagerLive[Req](
    behavior: (String, Queue[Req]) => Task[Nothing],
    terminateMessage: Promise[Nothing, Unit] => Option[Req],
    entities: Ref.Synchronized[Map[String, (Option[Queue[Req]], Fiber[Nothing, Unit])]],
    sharding: Sharding
  ) extends EntityManager[Req] {
    private def startExpirationFiber(entityId: String): UIO[Fiber[Nothing, Unit]] =
      (for {
        _ <- Clock.sleep(2 minutes)
        _ <- terminateEntity(entityId).forkDaemon.unit // fork daemon otherwise it will interrupt itself
      } yield ()).forkDaemon

    private def terminateEntity(entityId: String): UIO[Unit] =
      entities.updateZIO(map =>
        map.get(entityId) match {
          case Some((Some(queue), interruptionFiber)) =>
            // if a queue is found, offer the termination message, and set the queue to None so that no new message is enqueued
            Promise
              .make[Nothing, Unit]
              .flatMap(p => ZIO.foreach(terminateMessage(p))(queue.offer).exit)
              .as(map.updated(entityId, (None, interruptionFiber)))
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
                                case None            => p.succeed(())
                              }
                            case None        => p.succeed(())
                          }
                        )
                    }
        // wait until they are all terminated
        _        <- ZIO.foreachDiscard(promises)(_.await).timeout(3 seconds)
      } yield ()
  }
}
