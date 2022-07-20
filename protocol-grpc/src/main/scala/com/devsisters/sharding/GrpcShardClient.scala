package com.devsisters.sharding

import com.devsisters.sharding.errors._
import com.devsisters.sharding.interfaces.ShardClient
import com.devsisters.sharding.interfaces.ShardClient.BinaryMessage
import com.devsisters.sharding.protobuf.sharding.SendRequest
import com.devsisters.sharding.protobuf.sharding.ZioSharding.ShardingServiceClient
import com.google.protobuf.ByteString
import io.grpc.{ ManagedChannelBuilder, Status }
import scalapb.zio_grpc.ZManagedChannel
import zio._

import java.time.OffsetDateTime

object GrpcShardClient {
  val live: ZLayer[Config with ShardManagerClient, Throwable, ShardClient] =
    ZLayer.scoped {
      for {
        shardManager              <- ZIO.service[ShardManagerClient]
        config                    <- ZIO.service[Config]
        connections               <-
          Ref.Synchronized
            .make(Map.empty[PodAddress, (ShardingServiceClient.ZService[Any, Any], Fiber[Throwable, Nothing])])
            // stop all connection fibers on release
            .withFinalizer(
              _.get.flatMap(connections => ZIO.foreachDiscard(connections) { case (_, (_, fiber)) => fiber.interrupt })
            )
        cdt                       <- Clock.currentDateTime
        lastUnhealthyNodeReported <- Ref.make(cdt)
      } yield new ShardClientLive(
        config,
        shardManager,
        connections,
        lastUnhealthyNodeReported
      )
    }

  val local: ZLayer[Config, Nothing, ShardClient] =
    ZLayer.succeed(new ShardClient {
      def sendMessage(message: BinaryMessage, pod: PodAddress): Task[Option[Array[Byte]]] = ZIO.none
    })

  class ShardClientLive(
    config: Config,
    shardManager: ShardManagerClient,
    connections: Ref.Synchronized[
      Map[PodAddress, (ShardingServiceClient.ZService[Any, Any], Fiber[Throwable, Nothing])]
    ],
    lastUnhealthyNodeReported: Ref[OffsetDateTime]
  ) extends ShardClient {
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

    def sendMessage(message: BinaryMessage, pod: PodAddress): Task[Option[Array[Byte]]] =
      getConnection(pod)
        .flatMap(
          _.send(SendRequest(message.entityId, message.entityType, ByteString.copyFrom(message.body)))
            .foldZIO(
              status =>
                if (status.getCode == Status.Code.RESOURCE_EXHAUSTED) {
                  // entity is not managed by this pod, wait and retry (assignments will be updated)
                  ZIO.fail(EntityNotManagedByThisPod(message.entityId))
                } else if (status.getCode == Status.Code.UNAVAILABLE || status.getCode == Status.Code.CANCELLED) {
                  // notify shard manager that node is unreachable
                  // only do it once per 5 seconds
                  val notify = Clock.currentDateTime.flatMap(cdt =>
                    lastUnhealthyNodeReported
                      .updateAndGet(old => if (old.plusSeconds(5) isBefore cdt) cdt else old)
                      .map(_ isEqual cdt)
                  )

                  ZIO.whenZIO(notify)(shardManager.notifyUnhealthyPod(pod).forkDaemon) *>
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
}
