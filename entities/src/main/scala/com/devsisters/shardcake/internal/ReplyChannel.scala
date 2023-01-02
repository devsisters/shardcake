package com.devsisters.shardcake.internal

import zio.stream.{ Take, ZStream }
import zio.{ Cause, Promise, Queue, Task, UIO }

private[shardcake] sealed trait ReplyChannel[-A] { self =>
  val await: UIO[Unit]
  val end: UIO[Unit]
  def fail(cause: Cause[Throwable]): UIO[Unit]
  protected def reply(a: A): UIO[Unit]

  def replySingle(a: A): UIO[Unit] =
    reply(a) *> end

  def replyStream(stream: ZStream[Any, Throwable, A]): UIO[Unit] =
    (stream.runForeach(reply) *> end).catchAllCause(fail).fork.unit
}

private[shardcake] object ReplyChannel {
  case class FromQueue[A](queue: Queue[Take[Throwable, A]]) extends ReplyChannel[A] {
    val await: UIO[Unit]                         = queue.awaitShutdown
    val end: UIO[Unit]                           = queue.offer(Take.end).unit
    def fail(cause: Cause[Throwable]): UIO[Unit] = queue.offer(Take.failCause(cause)).unit
    def reply(a: A): UIO[Unit]                   = queue.offer(Take.single(a)).unit
    val output: ZStream[Any, Throwable, A]       = ZStream.fromQueue(queue).flattenTake
  }

  case class FromPromise[A](promise: Promise[Throwable, Option[A]]) extends ReplyChannel[A] {
    val await: UIO[Unit]                         = promise.await.exit.unit
    val end: UIO[Unit]                           = promise.succeed(None).unit
    def fail(cause: Cause[Throwable]): UIO[Unit] = promise.failCause(cause).unit
    def reply(a: A): UIO[Unit]                   = promise.succeed(Some(a)).unit
    val output: Task[Option[A]]                  = promise.await
  }

  def single[A]: UIO[FromPromise[A]] =
    Promise.make[Throwable, Option[A]].map(FromPromise(_))

  def stream[A]: UIO[FromQueue[A]] =
    Queue.unbounded[Take[Throwable, A]].map(FromQueue(_))
}
