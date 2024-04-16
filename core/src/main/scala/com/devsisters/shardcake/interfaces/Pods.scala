package com.devsisters.shardcake.interfaces

import com.devsisters.shardcake.interfaces.Pods.BinaryMessage
import com.devsisters.shardcake.{ PodAddress, ShardId }
import zio.stream.ZStream
import zio.{ Task, ULayer, ZIO, ZLayer }

/**
 * An interface to communicate with remote pods.
 * This is used by the Shard Manager for assigning and unassigning shards.
 * This is also used by pods for internal communication (forward messages to each other).
 */
trait Pods {

  /**
   * Notify a pod that it was assigned a list of shards
   */
  def assignShards(pod: PodAddress, shards: Set[ShardId]): Task[Unit]

  /**
   * Notify a pod that it was unassigned a list of shards
   */
  def unassignShards(pod: PodAddress, shards: Set[ShardId]): Task[Unit]

  /**
   * Check that a pod is responsive
   */
  def ping(pod: PodAddress): Task[Unit]

  /**
   * Send a message to a pod
   */
  def sendMessage(pod: PodAddress, message: BinaryMessage): Task[Option[Array[Byte]]]

  /**
   * Send a stream of messages to a pod
   */
  def sendStream(
    pod: PodAddress,
    entityId: String,
    messages: ZStream[Any, Throwable, BinaryMessage]
  ): Task[Option[Array[Byte]]]

  /**
   * Send a message to a pod and receive a stream of replies
   */
  def sendMessageAndReceiveStream(pod: PodAddress, message: BinaryMessage): ZStream[Any, Throwable, Array[Byte]]

  /**
   * Send a stream of messages to a pod and receive a stream of replies
   */
  def sendStreamAndReceiveStream(
    pod: PodAddress,
    entityId: String,
    messages: ZStream[Any, Throwable, BinaryMessage]
  ): ZStream[Any, Throwable, Array[Byte]]
}

object Pods {

  /**
   * A layer that creates a service that does nothing when called.
   * Useful for testing ShardManager or when using Sharding.local.
   */
  val noop: ULayer[Pods] =
    ZLayer.succeed(new Pods {
      def assignShards(pod: PodAddress, shards: Set[ShardId]): Task[Unit]                                            = ZIO.unit
      def unassignShards(pod: PodAddress, shards: Set[ShardId]): Task[Unit]                                          = ZIO.unit
      def ping(pod: PodAddress): Task[Unit]                                                                          = ZIO.unit
      def sendMessage(pod: PodAddress, message: BinaryMessage): Task[Option[Array[Byte]]]                            = ZIO.none
      def sendStream(
        pod: PodAddress,
        entityId: String,
        messages: ZStream[Any, Throwable, BinaryMessage]
      ): Task[Option[Array[Byte]]] = ZIO.none
      def sendMessageAndReceiveStream(pod: PodAddress, message: BinaryMessage): ZStream[Any, Throwable, Array[Byte]] =
        ZStream.empty
      def sendStreamAndReceiveStream(
        pod: PodAddress,
        entityId: String,
        messages: ZStream[Any, Throwable, BinaryMessage]
      ): ZStream[Any, Throwable, Array[Byte]] = ZStream.empty
    })

  case class BinaryMessage(entityId: String, entityType: String, body: Array[Byte], replyId: Option[String])
}
