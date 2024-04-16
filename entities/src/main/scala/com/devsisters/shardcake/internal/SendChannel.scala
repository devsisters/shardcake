package com.devsisters.shardcake.internal

import com.devsisters.shardcake.PodAddress
import com.devsisters.shardcake.interfaces.Pods.BinaryMessage
import com.devsisters.shardcake.interfaces.{ Pods, Serialization }
import zio.Task
import zio.stream.ZStream

private[shardcake] sealed trait SendChannel[+A] { self =>
  def foreach(f: A => Task[Unit]): Task[Unit]
  def send(
    pods: Pods,
    serialization: Serialization,
    pod: PodAddress,
    entityId: String,
    recipientTypeName: String,
    replyId: Option[String]
  ): Task[Option[Array[Byte]]]
  def sendAndReceiveStream(
    pods: Pods,
    serialization: Serialization,
    pod: PodAddress,
    entityId: String,
    recipientTypeName: String,
    replyId: Option[String]
  ): ZStream[Any, Throwable, Array[Byte]]
}

private[shardcake] object SendChannel {
  case class Single[A](msg: A)                               extends SendChannel[A] {
    def foreach(f: A => Task[Unit]): Task[Unit] = f(msg)
    def send(
      pods: Pods,
      serialization: Serialization,
      pod: PodAddress,
      entityId: String,
      recipientTypeName: String,
      replyId: Option[String]
    ): Task[Option[Array[Byte]]] =
      serialization
        .encode(msg)
        .flatMap(bytes => pods.sendMessage(pod, BinaryMessage(entityId, recipientTypeName, bytes, replyId)))
    def sendAndReceiveStream(
      pods: Pods,
      serialization: Serialization,
      pod: PodAddress,
      entityId: String,
      recipientTypeName: String,
      replyId: Option[String]
    ): ZStream[Any, Throwable, Array[Byte]] =
      ZStream.unwrap(
        serialization
          .encode(msg)
          .map { bytes =>
            val binaryMessage = BinaryMessage(entityId, recipientTypeName, bytes, replyId)
            pods.sendMessageAndReceiveStream(pod, binaryMessage)
          }
      )
  }
  case class Stream[A](messages: ZStream[Any, Throwable, A]) extends SendChannel[A] {
    def foreach(f: A => Task[Unit]): Task[Unit] = messages.runForeach(f)
    def send(
      pods: Pods,
      serialization: Serialization,
      pod: PodAddress,
      entityId: String,
      recipientTypeName: String,
      replyId: Option[String]
    ): Task[Option[Array[Byte]]] =
      pods.sendStream(
        pod,
        entityId,
        messages.mapChunksZIO(messages =>
          serialization
            .encodeChunk(messages)
            .map(_.map(bytes => BinaryMessage(entityId, recipientTypeName, bytes, replyId)))
        )
      )
    def sendAndReceiveStream(
      pods: Pods,
      serialization: Serialization,
      pod: PodAddress,
      entityId: String,
      recipientTypeName: String,
      replyId: Option[String]
    ): ZStream[Any, Throwable, Array[Byte]] = {
      val requestStream = messages.mapChunksZIO(messages =>
        serialization
          .encodeChunk(messages)
          .map(_.map(bytes => BinaryMessage(entityId, recipientTypeName, bytes, replyId)))
      )
      pods.sendStreamAndReceiveStream(pod, entityId, requestStream)
    }
  }

  def single[A](msg: A): SendChannel[A] =
    Single(msg)

  def stream[A](messages: ZStream[Any, Throwable, A]): SendChannel[A] =
    Stream(messages)
}
