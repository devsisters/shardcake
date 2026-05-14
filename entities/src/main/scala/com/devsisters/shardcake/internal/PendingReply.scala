package com.devsisters.shardcake.internal

import zio.{ Cause, UIO }

/**
 * A reply we're waiting on. Wraps the [[ReplyChannel]] that the incoming response will
 * be pushed into, and records whether to push it as a raw typed value (local sends, no
 * serialisation) or as pre-encoded bytes (remote sends, where the encoder supplied by
 * the codec is applied).
 */
private[shardcake] sealed trait PendingReply {
  protected def channel: ReplyChannel[?]
  def fail(cause: Cause[Throwable]): UIO[Unit] = channel.fail(cause)
  val await: UIO[Unit]                         = channel.await
  val end: UIO[Unit]                           = channel.end
}

private[shardcake] object PendingReply {
  final case class Value(channel: ReplyChannel[Nothing])                                 extends PendingReply
  final case class Bytes(channel: ReplyChannel[Array[Byte]], encode: Any => Array[Byte]) extends PendingReply
}
