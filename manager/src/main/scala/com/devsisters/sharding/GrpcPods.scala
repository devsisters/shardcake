package com.devsisters.sharding

import com.devsisters.sharding.interfaces.Pods
import com.devsisters.sharding.protobuf.sharding.ZioSharding.ShardingServiceClient
import com.devsisters.sharding.protobuf.sharding._
import io.grpc.ManagedChannelBuilder
import scalapb.zio_grpc.ZManagedChannel
import zio.{ RIO, Scope, Task, ULayer, ZIO, ZLayer }

object GrpcPods {
  val live: ULayer[Pods] =
    ZLayer.succeed(
      new Pods {
        private def makeClient(pod: PodAddress): RIO[Scope, ShardingServiceClient.ZService[Any, Any]] =
          ShardingServiceClient.scoped(
            ZManagedChannel.apply(ManagedChannelBuilder.forAddress(pod.host, pod.port).usePlaintext())
          )

        def assignShards(pod: PodAddress, shards: Set[ShardId]): Task[Unit] =
          ZIO.scoped(
            makeClient(pod).flatMap(_.assignShards(AssignShardsRequest(shards.toSeq)).unit.mapError(_.asException()))
          )

        def unassignShards(pod: PodAddress, shards: Set[ShardId]): Task[Unit] =
          ZIO.scoped(
            makeClient(pod).flatMap(
              _.unassignShards(UnassignShardsRequest(shards.toSeq)).unit.mapError(_.asException())
            )
          )

        def ping(pod: PodAddress): Task[Unit] =
          ZIO.scoped(makeClient(pod).flatMap(_.pingShards(PingShardsRequest()).unit.mapError(_.asException())))
      }
    )
}
