package com.devsisters.sharding.internal

import com.devsisters.sharding.{ EntityType, ShardId }
import zio._
import zio.stream.ZStream

private[sharding] class ShardHub(
  _queues: Ref.Synchronized[Map[String, Queue[ShardEvent]]],                // queue for each local entity manager
  _promises: Ref.Synchronized[Map[String, Promise[Throwable, Option[Any]]]] // promise for each pending reply
) {

  private def getEntityManagerQueue(entityType: String): UIO[Queue[ShardEvent]] =
    // optimize happy path and only get first
    _queues.get.flatMap(_.get(entityType) match {
      case Some(queue) => ZIO.succeed(queue)
      case None        =>
        // then do modify in the case it doesn't already exist
        _queues.modifyZIO(queues =>
          queues.get(entityType) match {
            case Some(queue) => ZIO.succeed((queue, queues))
            case None        => Queue.unbounded[ShardEvent].map(queue => (queue, queues.updated(entityType, queue)))
          }
        )
    })

  def sendToLocalEntity(entityId: String, entityType: String, msg: Any): Task[Option[Any]] =
    for {
      queue  <- getEntityManagerQueue(entityType)
      p      <- Promise.make[Throwable, Option[Any]]
      _      <- queue.offer(ShardEvent.NewMessage(msg, entityId, p))
      result <- p.await
    } yield result

  def getLocalShardEvents(entityType: EntityType): ZStream[Any, Nothing, ShardEvent] =
    ZStream.fromZIO(getEntityManagerQueue(entityType.value)).flatMap(ZStream.fromQueue(_))

  def initReply(id: String, promise: Promise[Throwable, Option[Any]], context: String): UIO[Unit] =
    _promises.update(promises => promises.updated(id, promise)) <*
      promise.await
        .timeoutFail(new Exception(s"Promise was not completed in time. $context"))(12 seconds) // > ask timeout
        .onError(cause => abortReply(id, cause.squash))
        .forkDaemon

  def reply(id: String, resp: Any): UIO[Unit] =
    _promises.updateZIO(promises =>
      ZIO.whenCase(promises.get(id)) { case Some(p) => p.succeed(Some(resp)) }.as(promises - id)
    )

  def abortReply(id: String, ex: Throwable): UIO[Unit] =
    _promises.updateZIO(promises => ZIO.whenCase(promises.get(id)) { case Some(p) => p.fail(ex) }.as(promises - id))

  def terminateShards(shards: Set[ShardId]): UIO[Unit] =
    for {
      queues   <- _queues.get
      promises <- ZIO.foreach(queues.toList) { case (_, queue) =>
                    Promise.make[Nothing, Unit].tap(p => queue.offer(ShardEvent.TerminateShards(shards, p)))
                  }
      _        <- ZIO.foreachDiscard(promises)(_.await)
    } yield ()

  def shutdown: UIO[Unit] =
    for {
      queues   <- _queues.get
      promises <- ZIO.foreach(queues.toList) { case (_, queue) =>
                    Promise.make[Nothing, Unit].tap(p => queue.offer(ShardEvent.Shutdown(p)))
                  }
      _        <- ZIO.foreachDiscard(promises)(_.await)
    } yield ()
}

private[sharding] object ShardHub {
  val make: UIO[ShardHub] =
    for {
      queues   <- Ref.Synchronized.make[Map[String, Queue[ShardEvent]]](Map())
      promises <- Ref.Synchronized.make[Map[String, Promise[Throwable, Option[Any]]]](Map())
    } yield new ShardHub(queues, promises)
}
