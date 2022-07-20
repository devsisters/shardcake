package com.devsisters.sharding

import caliban.client.Operations.IsOperation
import caliban.client.SelectionBuilder
import com.devsisters.sharding.internal.GraphQLClient.PodAddressInput
import com.devsisters.sharding.interfaces.Serialization
import com.devsisters.sharding.internal.{ GraphQLClient, ShardHub }
import com.devsisters.sharding.protobuf.sharding.SendRequest
import com.devsisters.sharding.protobuf.sharding.ZioSharding.ShardingServiceClient
import com.google.protobuf.ByteString
import io.grpc.{ ManagedChannelBuilder, Status }
import scalapb.zio_grpc.ZManagedChannel
import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.client3.SttpBackend
import zio._

import java.time.OffsetDateTime

trait ShardClient {
  // communication with Shard Manager (GraphQL)
  def register(podAddress: PodAddress): Task[Unit]
  def unregister(podAddress: PodAddress): Task[Unit]
  def notifyUnhealthyPod(podAddress: PodAddress): Task[Unit]
  def getAssignments: Task[Map[Int, Option[PodAddress]]]

  // communication with other nodes (gRPC)
  def sendMessage(message: Message, pod: PodAddress): Task[Option[Any]]
}

object ShardClient {
  val live
    : ZLayer[Config with SttpBackend[Task, ZioStreams with WebSockets] with Serialization, Throwable, ShardClient] =
    ZLayer.scoped {
      for {
        sttpClient                <- ZIO.service[SttpBackend[Task, ZioStreams with WebSockets]]
        serialization             <- ZIO.service[Serialization]
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
        sttpClient,
        serialization,
        config,
        connections,
        lastUnhealthyNodeReported
      )
    }

  val local: ZLayer[ShardHub with Config, Nothing, ShardClient] =
    ZLayer {
      for {
        shardHub <- ZIO.service[ShardHub]
        config   <- ZIO.service[Config]
        pod       = PodAddress(config.selfHost, config.shardingPort)
        shards    = (1 to config.numberOfShards).map(_ -> Some(pod)).toMap
      } yield new ShardClient {
        def register(podAddress: PodAddress): Task[Unit]                      = ZIO.unit
        def unregister(podAddress: PodAddress): Task[Unit]                    = ZIO.unit
        def notifyUnhealthyPod(podAddress: PodAddress): Task[Unit]            = ZIO.unit
        def getAssignments: Task[Map[Int, Option[PodAddress]]]                = ZIO.succeed(shards)
        def sendMessage(message: Message, pod: PodAddress): Task[Option[Any]] =
          shardHub.sendToLocalEntity(message.entityId, message.entityType.value, message.body)
      }
    }

  class ShardClientLive(
    sttp: SttpBackend[Task, ZioStreams with WebSockets],
    serialization: Serialization,
    config: Config,
    connections: Ref.Synchronized[
      Map[PodAddress, (ShardingServiceClient.ZService[Any, Any], Fiber[Throwable, Nothing])]
    ],
    lastUnhealthyNodeReported: Ref[OffsetDateTime]
  ) extends ShardClient {
    private def send[Origin: IsOperation, A](query: SelectionBuilder[Origin, A]): Task[A] =
      sttp.send(query.toRequest(config.shardManagerUri)).map(_.body).absolve

    def register(podAddress: PodAddress): Task[Unit] =
      send(
        GraphQLClient.Mutations.register(PodAddressInput(podAddress.host, podAddress.port), config.serverVersion)
      ).unit

    def unregister(podAddress: PodAddress): Task[Unit] =
      send(
        GraphQLClient.Mutations.unregister(PodAddressInput(podAddress.host, podAddress.port), config.serverVersion)
      ).unit

    def notifyUnhealthyPod(podAddress: PodAddress): Task[Unit] =
      ZIO.logWarning(s"Notifying Shard Manager about unhealthy pod $podAddress") *>
        send(GraphQLClient.Mutations.notifyUnhealthyPod(PodAddressInput(podAddress.host, podAddress.port)))

    def getAssignments: Task[Map[Int, Option[PodAddress]]] =
      send(
        GraphQLClient.Queries
          .getAssignments(
            GraphQLClient.Assignment.shardId ~ GraphQLClient.Assignment
              .pod((GraphQLClient.PodAddress.host ~ GraphQLClient.PodAddress.port).map { case (host, port) =>
                PodAddress(host, port)
              })
          )
          .map(_.toMap)
      )

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

    def sendMessage(message: Message, pod: PodAddress): Task[Option[Any]] =
      serialization
        .encode(message.body)
        .flatMap(bytes =>
          getConnection(pod)
            .flatMap(
              _.send(SendRequest(message.entityId, message.entityType.value, ByteString.copyFrom(bytes)))
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

                      ZIO.whenZIO(notify)(notifyUnhealthyPod(pod).forkDaemon) *>
                        ZIO.fail(PodUnavailable(pod))
                    } else {
                      ZIO.fail(status.asException())
                    },
                  res =>
                    if (res.body.isEmpty) ZIO.none
                    else serialization.decode(res.body.toByteArray).asSome
                )
            )
        )
  }
}
