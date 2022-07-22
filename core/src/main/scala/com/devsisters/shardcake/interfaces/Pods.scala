package com.devsisters.shardcake.interfaces

import com.devsisters.shardcake.interfaces.Pods.BinaryMessage
import com.devsisters.shardcake.{ PodAddress, ShardId }
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
}

object Pods {

  /**
   * A layer that creates a service that does nothing when called.
   * Useful for testing ShardManager or when using Sharding.local.
   */
  val noop: ULayer[Pods] =
    ZLayer.succeed(new Pods {
      def assignShards(pod: PodAddress, shards: Set[ShardId]): Task[Unit]                 = ZIO.unit
      def unassignShards(pod: PodAddress, shards: Set[ShardId]): Task[Unit]               = ZIO.unit
      def ping(pod: PodAddress): Task[Unit]                                               = ZIO.unit
      def sendMessage(pod: PodAddress, message: BinaryMessage): Task[Option[Array[Byte]]] = ZIO.none
    })

  case class BinaryMessage(entityId: String, entityType: String, body: Array[Byte], replyId: Option[String])
}
