package com.devsisters.sharding

import com.devsisters.sharding.errors._
import com.devsisters.sharding.interfaces.Pods
import com.devsisters.sharding.interfaces.Pods.BinaryMessage
import com.devsisters.sharding.protobuf.sharding._
import com.devsisters.sharding.protobuf.sharding.ZioSharding.ShardingServiceClient
import com.google.protobuf.ByteString
import io.grpc.{ ManagedChannelBuilder, Status }
import scalapb.zio_grpc.ZManagedChannel
import zio._

class GrpcPods(
  config: GrpcConfig,
  connections: Ref.Synchronized[Map[PodAddress, (ShardingServiceClient.ZService[Any, Any], Fiber[Throwable, Nothing])]]
) extends Pods {
  private def getConnection(pod: PodAddress): Task[ShardingServiceClient.ZService[Any, Any]] =
    // optimize happy path and only get first
    connections.get.flatMap(_.get(pod) match {
      case Some((channel, _)) => ZIO.succeed(channel)
      case None               =>
        // then do modify in the case it doesn't already exist
        connections.modifyZIO { map =>
          map.get(pod) match {
            case Some((channel, _)) => ZIO.succeed((channel, map))
            case None               =>
              val channel: ZManagedChannel[Any] =
                ZManagedChannel.apply(
                  ManagedChannelBuilder
                    .forAddress(pod.host, pod.port)
                    .maxInboundMessageSize(config.maxInboundMessageSize)
                    .usePlaintext()
                )
              // create a fiber that never ends and keeps the connection alive
              for {
                _          <- ZIO.logDebug(s"Opening connection to pod $pod")
                promise    <- Promise.make[Nothing, ShardingServiceClient.ZService[Any, Any]]
                fiber      <-
                  ZIO
                    .scoped(
                      ShardingServiceClient
                        .scoped(channel)
                        .flatMap(promise.succeed(_) *> ZIO.never)
                        .ensuring(connections.update(_ - pod) *> ZIO.logDebug(s"Closed connection to pod $pod"))
                    )
                    .forkDaemon
                connection <- promise.await
              } yield (connection, map.updated(pod, (connection, fiber)))
          }
        }
    })

  def assignShards(pod: PodAddress, shards: Set[ShardId]): Task[Unit] =
    getConnection(pod).flatMap(_.assignShards(AssignShardsRequest(shards.toSeq)).unit.mapError(_.asException()))

  def unassignShards(pod: PodAddress, shards: Set[ShardId]): Task[Unit] =
    getConnection(pod).flatMap(
      _.unassignShards(UnassignShardsRequest(shards.toSeq)).unit.mapError(_.asException())
    )

  def ping(pod: PodAddress): Task[Unit] =
    getConnection(pod).flatMap(_.pingShards(PingShardsRequest()).unit.mapError(_.asException()))

  def sendMessage(pod: PodAddress, message: BinaryMessage): Task[Option[Array[Byte]]] =
    getConnection(pod)
      .flatMap(
        _.send(SendRequest(message.entityId, message.entityType, ByteString.copyFrom(message.body), message.replyId))
          .foldZIO(
            status =>
              if (status.getCode == Status.Code.RESOURCE_EXHAUSTED) {
                // entity is not managed by this pod, wait and retry (assignments will be updated)
                ZIO.fail(EntityNotManagedByThisPod(message.entityId))
              } else if (status.getCode == Status.Code.UNAVAILABLE || status.getCode == Status.Code.CANCELLED) {
                ZIO.fail(PodUnavailable(pod))
              } else {
                ZIO.fail(status.asException())
              },
            res =>
              if (res.body.isEmpty) ZIO.none
              else ZIO.some(res.body.toByteArray)
          )
      )
}

object GrpcPods {
  val live: ZLayer[GrpcConfig, Throwable, Pods] =
    ZLayer.scoped {
      for {
        config      <- ZIO.service[GrpcConfig]
        connections <-
          Ref.Synchronized
            .make(Map.empty[PodAddress, (ShardingServiceClient.ZService[Any, Any], Fiber[Throwable, Nothing])])
            .withFinalizer(
              // stop all connection fibers on release
              _.get.flatMap(connections => ZIO.foreachDiscard(connections) { case (_, (_, fiber)) => fiber.interrupt })
            )
      } yield new GrpcPods(config, connections)
    }
}
