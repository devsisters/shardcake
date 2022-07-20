package com.devsisters.sharding.interfaces

import com.devsisters.sharding.{ PodAddress, ShardId }
import zio.{ Task, ULayer, ZIO, ZLayer }

trait Pods {
  def assignShards(pod: PodAddress, shards: Set[ShardId]): Task[Unit]
  def unassignShards(pod: PodAddress, shards: Set[ShardId]): Task[Unit]
  def ping(pod: PodAddress): Task[Unit]
}

object Pods {
  val noop: ULayer[Pods] =
    ZLayer.succeed(new Pods {
      def assignShards(pod: PodAddress, shards: Set[ShardId]): Task[Unit]   = ZIO.unit
      def unassignShards(pod: PodAddress, shards: Set[ShardId]): Task[Unit] = ZIO.unit
      def ping(pod: PodAddress): Task[Unit]                                 = ZIO.unit
    })
}
