package com.devsisters.shardcake.internal

import com.devsisters.shardcake.interfaces.Serialization
import zio.stream.{ Take, ZStream }
import zio.{ Cause, Promise, Queue, Task, UIO }

private[shardcake] sealed trait ReplyChannel[A] { self =>
  val await: UIO[Unit]
  val end: UIO[Unit]
  def fail(cause: Cause[Throwable]): UIO[Unit]
  def replySingle(a: A)(implicit serialization: Serialization[A]): UIO[Unit]
  def replyStream(stream: ZStream[Any, Throwable, A])(implicit serialization: Serialization[A]): UIO[Unit]
}

private[shardcake] object ReplyChannel {
  case class FromQueue[A](queue: Queue[Take[Throwable, A]], serializationPromise: Promise[Throwable, Serialization[A]])
      extends ReplyChannel[A] {
    val await: UIO[Unit]                                                       = queue.awaitShutdown
    val end: UIO[Unit]                                                         = queue.offer(Take.end).exit.unit
    def fail(cause: Cause[Throwable]): UIO[Unit]                               =
      serializationPromise.failCause(cause) *> queue.offer(Take.failCause(cause)).exit.unit
    def replySingle(a: A)(implicit serialization: Serialization[A]): UIO[Unit] =
      serializationPromise.succeed(serialization) *>
        queue.offer(Take.single(a)).exit *> end

    def replyStream(stream: ZStream[Any, Throwable, A])(implicit serialization: Serialization[A]): UIO[Unit] =
      serializationPromise.succeed(serialization) *>
        (stream
          .runForeachChunk(chunk => queue.offer(Take.chunk(chunk)))
          .onExit(e => queue.offer(e.foldExit(Take.failCause, _ => Take.end)))
          .ignore race await).fork.unit
    val output: (Promise[Throwable, Serialization[A]], ZStream[Any, Throwable, A])                           =
      serializationPromise ->
        ZStream.fromQueueWithShutdown(queue).flattenTake.onError(fail)
  }

  case class FromPromise[A](promise: Promise[Throwable, Option[(A, Serialization[A])]]) extends ReplyChannel[A] {
    val await: UIO[Unit]                                                                                     = promise.await.exit.unit
    val end: UIO[Unit]                                                                                       = promise.succeed(None).unit
    def fail(cause: Cause[Throwable]): UIO[Unit]                                                             = promise.failCause(cause).unit
    def replySingle(a: A)(implicit serialization: Serialization[A]): UIO[Unit]                               =
      promise.succeed(Some(a -> serialization)).unit
    def replyStream(stream: ZStream[Any, Throwable, A])(implicit serialization: Serialization[A]): UIO[Unit] =
      stream.runHead
        .flatMap(a => promise.succeed(a.map(_ -> serialization)).unit)
        .catchAllCause[Any, Nothing, Unit](fail)
        .fork
        .unit

    val output: Task[Option[(A, Serialization[A])]] = promise.await.onError(fail)
  }

  def single[A]: UIO[FromPromise[A]] =
    Promise.make[Throwable, Option[(A, Serialization[A])]].map(FromPromise(_))

  def stream[A]: UIO[FromQueue[A]] =
    Promise.make[Throwable, Serialization[A]].flatMap { serializationPromise =>
      Queue.unbounded[Take[Throwable, A]].map { queue =>
        FromQueue(queue, serializationPromise)
      }
    }
}
