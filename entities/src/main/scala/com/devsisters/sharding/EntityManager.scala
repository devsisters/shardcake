package com.devsisters.sharding

import com.devsisters.sharding.internal.ShardEvent
import izumi.reflect.Tag
import zio._

class EntityManager[Req](
  behavior: (String, Queue[Req]) => Task[Nothing],
  replyTo: Req => Option[String],
  terminateMessage: Promise[Nothing, Unit] => Req,
  entities: Ref.Synchronized[Map[String, (Option[Queue[Req]], Fiber[Nothing, Unit])]],
  sharding: Sharding
) {
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
            .flatMap(p => queue.offer(terminateMessage(p)).exit)
            .as(map.updated(entityId, (None, interruptionFiber)))
        case _                                      =>
          // if no queue is found, do nothing
          ZIO.succeed(map)
      }
    )

  private def send(
    entityId: String,
    req: Req,
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
                     ZIO.succeed(None, map)
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
                   Clock.sleep(100 millis) *> send(entityId, req, promise)
                 case Some(queue) =>
                   // add the message to the queue and setup the response promise if needed
                   (replyTo(req) match {
                     case Some(id) =>
                       sharding.hub.initReply(id, promise, s"entityId: $entityId, req: $req") *> queue.offer(req)
                     case None     => queue.offer(req) *> promise.succeed(None)
                   }).catchAllCause(_ => send(entityId, req, promise))
               }
    } yield ()

  private def terminateShards(shards: Set[ShardId]): UIO[Unit] =
    entities.modify { entities =>
      // get all entities on the given shards to terminate them
      entities.partition { case (entityId, _) => shards.contains(sharding.getShardId(entityId)) }
    }
      .flatMap(terminateEntities)

  private def terminateAllShards: UIO[Unit] =
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
                        ZIO.foreachDiscard(queue)(_.offer(terminateMessage(p)).catchAllCause(_ => p.succeed(())))
                      )
                  }
      // wait until they are all terminated
      _        <- ZIO.foreachDiscard(promises)(_.await).timeout(3 seconds)
    } yield ()

  def start(entityType: EntityType): UIO[Unit] =
    sharding.hub
      .getLocalShardEvents(entityType)
      .mapZIO {
        case ShardEvent.NewMessage(req, entityId, p) => send(entityId, req.asInstanceOf[Req], p).catchAll(p.fail).fork
        case ShardEvent.TerminateShards(shards, p)   => (terminateShards(shards) *> p.succeed(())).fork
        case ShardEvent.Shutdown(p)                  => (terminateAllShards *> p.succeed(())).fork
      }
      .runDrain
}

object EntityManager {
  def live[Req: Tag](
    entityType: EntityType,
    behavior: (String, Queue[Req]) => Task[Nothing],
    replyTo: Req => Option[String],
    terminateMessage: Promise[Nothing, Unit] => Req
  ): ZLayer[Sharding, Nothing, EntityManager[Req]] =
    ZLayer {
      for {
        sharding <- ZIO.service[Sharding]
        entities <- Ref.Synchronized.make[Map[String, (Option[Queue[Req]], Fiber[Nothing, Unit])]](Map())
        manager   = new EntityManager[Req](behavior, replyTo, terminateMessage, entities, sharding)
        _        <- manager.start(entityType).forkDaemon
      } yield manager
    }
}
