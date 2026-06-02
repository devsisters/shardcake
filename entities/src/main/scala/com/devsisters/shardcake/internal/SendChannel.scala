package com.devsisters.shardcake.internal

import com.devsisters.shardcake.PodAddress
import com.devsisters.shardcake.interfaces.{ MessageCodec, Pods }
import com.devsisters.shardcake.interfaces.Pods.BinaryMessage
import zio.{ Chunk, Task, ZIO }
import zio.stream.ZStream

private[shardcake] sealed trait SendChannel[A] { self =>
  def foreach(f: A => Task[Unit]): Task[Unit]
  def send(
    pods: Pods,
    codec: MessageCodec[A],
    pod: PodAddress,
    entityId: String,
    recipientTypeName: String,
    replyId: Option[String]
  ): Task[Option[Array[Byte]]]
  def sendAndReceiveStream(
    pods: Pods,
    codec: MessageCodec[A],
    pod: PodAddress,
    entityId: String,
    recipientTypeName: String,
    replyId: Option[String]
  ): ZStream[Any, Throwable, Array[Byte]]
}

private[shardcake] object SendChannel {
  final case class Single[A](msg: A) extends SendChannel[A] {
    def foreach(f: A => Task[Unit]): Task[Unit] = f(msg)
    def send(
      pods: Pods,
      codec: MessageCodec[A],
      pod: PodAddress,
      entityId: String,
      recipientTypeName: String,
      replyId: Option[String]
    ): Task[Option[Array[Byte]]]                =
      ZIO
        .attempt(codec.encodeMessage(msg))
        .flatMap(bytes => pods.sendMessage(pod, BinaryMessage(entityId, recipientTypeName, bytes, replyId)))
    def sendAndReceiveStream(
      pods: Pods,
      codec: MessageCodec[A],
      pod: PodAddress,
      entityId: String,
      recipientTypeName: String,
      replyId: Option[String]
    ): ZStream[Any, Throwable, Array[Byte]]     =
      ZStream.unwrap(
        ZIO
          .attempt(codec.encodeMessage(msg))
          .map { bytes =>
            val binaryMessage = BinaryMessage(entityId, recipientTypeName, bytes, replyId)
            pods.sendMessageAndReceiveStream(pod, binaryMessage)
          }
      )
  }

  final case class Stream[A](messages: ZStream[Any, Throwable, A]) extends SendChannel[A] {
    def foreach(f: A => Task[Unit]): Task[Unit] = messages.runForeach(f)
    def send(
      pods: Pods,
      codec: MessageCodec[A],
      pod: PodAddress,
      entityId: String,
      recipientTypeName: String,
      replyId: Option[String]
    ): Task[Option[Array[Byte]]]                = {
      val requestStream = messages.mapChunksZIO(encodeChunk(codec, _, entityId, recipientTypeName, replyId))
      pods.sendStream(pod, entityId, requestStream)
    }
    def sendAndReceiveStream(
      pods: Pods,
      codec: MessageCodec[A],
      pod: PodAddress,
      entityId: String,
      recipientTypeName: String,
      replyId: Option[String]
    ): ZStream[Any, Throwable, Array[Byte]]     = {
      val requestStream = messages.mapChunksZIO(encodeChunk(codec, _, entityId, recipientTypeName, replyId))
      pods.sendStreamAndReceiveStream(pod, entityId, requestStream)
    }

    private def encodeChunk(
      codec: MessageCodec[A],
      chunk: Chunk[A],
      entityId: String,
      recipientTypeName: String,
      replyId: Option[String]
    ): Task[Chunk[BinaryMessage]] =
      ZIO.attempt(chunk.map(msg => BinaryMessage(entityId, recipientTypeName, codec.encodeMessage(msg), replyId)))
  }

  def single[A](msg: A): SendChannel[A] =
    Single(msg)

  def stream[A](messages: ZStream[Any, Throwable, A]): SendChannel[A] =
    Stream(messages)
}
