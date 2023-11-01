package com.devsisters.shardcake.internal

import com.devsisters.shardcake.errors.EntityNotManagedByThisPod
import com.devsisters.shardcake._
import zio.{ Config => _, _ }

import java.util.concurrent.TimeUnit

private[shardcake] trait EntityManager[-Req] {
  def send(
    entityId: String,
    req: Req,
    replyId: Option[String],
    replyChannel: ReplyChannel[Nothing]
  ): IO[EntityNotManagedByThisPod, Unit]
  def terminateEntitiesOnShards(shards: Set[ShardId]): UIO[Unit]
  def terminateAllEntities: UIO[Unit]
}

private[shardcake] object EntityManager {
  private type Signal = Promise[Nothing, Unit]

  def make[R, Req: Tag](
    recipientType: RecipientType[Req],
    behavior: (String, Queue[Req]) => RIO[R, Nothing],
    terminateMessage: Signal => Option[Req],
    sharding: Sharding,
    config: Config,
    entityMaxIdleTime: Option[Duration]
  ): URIO[R, EntityManager[Req]] =
    for {
      entities <- Ref.Synchronized.make[Map[String, (Either[Queue[Req], Signal], EpochMillis)]](Map())
      env      <- ZIO.environment[R]
    } yield new EntityManagerLive[Req](
      recipientType,
      (entityId: String, queue: Queue[Req]) => behavior(entityId, queue).provideEnvironment(env),
      terminateMessage,
      entities,
      sharding,
      config,
      entityMaxIdleTime
    )

  private def currentTimeInMilliseconds: UIO[EpochMillis] =
    Clock.currentTime(TimeUnit.MILLISECONDS)

  class EntityManagerLive[Req](
    recipientType: RecipientType[Req],
    behavior: (String, Queue[Req]) => Task[Nothing],
    terminateMessage: Signal => Option[Req],
    entities: Ref.Synchronized[Map[String, (Either[Queue[Req], Signal], EpochMillis)]],
    sharding: Sharding,
    config: Config,
    entityMaxIdleTime: Option[Duration]
  ) extends EntityManager[Req] {
    private def startExpirationFiber(entityId: String): UIO[Fiber[Nothing, Unit]] = {
      val maxIdleTime = entityMaxIdleTime getOrElse config.entityMaxIdleTime

      def sleep(duration: Duration): UIO[Unit] = for {
        _             <- Clock.sleep(duration)
        cdt           <- currentTimeInMilliseconds
        map           <- entities.get
        lastReceivedAt = map.get(entityId).map { case (_, lastReceivedAt) => lastReceivedAt }.getOrElse(0L)
        remaining      = maxIdleTime minus Duration.fromMillis(cdt - lastReceivedAt)
        _             <- sleep(remaining).when(remaining > Duration.Zero)
      } yield ()

      (for {
        _ <- sleep(maxIdleTime)
        _ <- terminateEntity(entityId).forkDaemon.unit // fork daemon otherwise it will interrupt itself
      } yield ()).interruptible.forkDaemon
    }

    private def terminateEntity(entityId: String): UIO[Unit] =
      entities.updateZIO(map =>
        map.get(entityId) match {
          case Some((Left(queue), lastReceivedAt)) =>
            Promise
              .make[Nothing, Unit]
              .flatMap { p =>
                terminateMessage(p) match {
                  case Some(msg) =>
                    // if a queue is found, offer the termination message, and set the queue to None so that no new message is enqueued
                    queue.offer(msg).exit.as(map.updated(entityId, (Right(p), lastReceivedAt)))
                  case None      =>
                    queue.shutdown.as(map - entityId)
                }
              }
          case _                                   =>
            // if no queue is found, do nothing
            ZIO.succeed(map)
        }
      )

    def send(
      entityId: String,
      req: Req,
      replyId: Option[String],
      replyChannel: ReplyChannel[Nothing]
    ): IO[EntityNotManagedByThisPod, Unit] =
      for {
        // first, verify that this entity should be handled by this pod
        _     <- recipientType match {
                   case _: EntityType[_] =>
                     ZIO.unlessZIO(sharding.isEntityOnLocalShards(recipientType, entityId))(
                       ZIO.fail(EntityNotManagedByThisPod(entityId))
                     )
                   case _: TopicType[_]  => ZIO.unit
                 }
        // find the queue for that entity, or create it if needed
        queue <- entities.modifyZIO(map =>
                   map.get(entityId) match {
                     case Some((queue @ Left(_), _)) =>
                       // queue exists, delay the interruption fiber and return the queue
                       currentTimeInMilliseconds.map(cdt => (queue, map.updated(entityId, (queue, cdt))))
                     case Some((p @ Right(_), _))    =>
                       // the queue is shutting down, stash and retry
                       ZIO.succeed((p, map))
                     case None                       =>
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
                             cdt             <- currentTimeInMilliseconds
                             leftQueue        = Left(queue)
                           } yield (leftQueue, map.updated(entityId, (leftQueue, cdt)))
                       }
                   }
                 )
        _     <- queue match {
                   case Right(_)    =>
                     // the queue is shutting down, try again a little later
                     Clock.sleep(100 millis) *> send(entityId, req, replyId, replyChannel)
                   case Left(queue) =>
                     // add the message to the queue and setup the reply channel if needed
                     (replyId match {
                       case Some(replyId) => sharding.initReply(replyId, replyChannel) *> queue.offer(req)
                       case None          => queue.offer(req) *> replyChannel.end
                     }).catchAllCause(_ => send(entityId, req, replyId, replyChannel))
                 }
      } yield ()

    def terminateEntitiesOnShards(shards: Set[ShardId]): UIO[Unit] =
      entities.modify { entities =>
        // get all entities on the given shards to terminate them
        entities.partition { case (entityId, _) => shards.contains(sharding.getShardId(recipientType, entityId)) }
      }
        .flatMap(terminateEntities)

    def terminateAllEntities: UIO[Unit] =
      entities.getAndSet(Map()).flatMap(terminateEntities)

    private def terminateEntities(
      entitiesToTerminate: Map[String, (Either[Queue[Req], Signal], EpochMillis)]
    ): UIO[Unit] =
      for {
        // send termination message to all entities
        promises <- ZIO.foreach(entitiesToTerminate.toList) { case (_, (queue, _)) =>
                      Promise
                        .make[Nothing, Unit]
                        .flatMap(p =>
                          queue match {
                            case Left(queue) =>
                              (terminateMessage(p) match {
                                case Some(terminate) => queue.offer(terminate).catchAllCause(_ => p.succeed(()))
                                case None            => queue.shutdown *> p.succeed(())
                              }).as(p)
                            case Right(p)    => ZIO.succeed(p)
                          }
                        )
                    }
        // wait until they are all terminated
        _        <- ZIO.foreachDiscard(promises)(_.await).timeout(config.entityTerminationTimeout)
      } yield ()
  }
}
