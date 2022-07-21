package com.devsisters.sharding

import caliban.client.Operations.IsOperation
import caliban.client.SelectionBuilder
import com.devsisters.sharding.internal.GraphQLClient
import com.devsisters.sharding.internal.GraphQLClient.PodAddressInput
import sttp.capabilities.WebSockets
import sttp.capabilities.zio.ZioStreams
import sttp.client3.SttpBackend
import sttp.client3.asynchttpclient.zio.AsyncHttpClientZioBackend
import zio._

trait ShardManagerClient {
  def register(podAddress: PodAddress): Task[Unit]
  def unregister(podAddress: PodAddress): Task[Unit]
  def notifyUnhealthyPod(podAddress: PodAddress): Task[Unit]
  def getAssignments: Task[Map[Int, Option[PodAddress]]]
}

object ShardManagerClient {
  val live: ZLayer[Config with SttpBackend[Task, ZioStreams with WebSockets], Nothing, ShardManagerClientLive] =
    ZLayer {
      for {
        sttpClient <- ZIO.service[SttpBackend[Task, ZioStreams with WebSockets]]
        config     <- ZIO.service[Config]
      } yield new ShardManagerClientLive(sttpClient, config)
    }

  val sttpLive: ZLayer[Config, Throwable, ShardManagerClient] =
    AsyncHttpClientZioBackend.layer() >>> live

  val local: ZLayer[Config, Nothing, ShardManagerClient] =
    ZLayer {
      for {
        config <- ZIO.service[Config]
        pod     = PodAddress(config.selfHost, config.shardingPort)
        shards  = (1 to config.numberOfShards).map(_ -> Some(pod)).toMap
      } yield new ShardManagerClient {
        def register(podAddress: PodAddress): Task[Unit]           = ZIO.unit
        def unregister(podAddress: PodAddress): Task[Unit]         = ZIO.unit
        def notifyUnhealthyPod(podAddress: PodAddress): Task[Unit] = ZIO.unit
        def getAssignments: Task[Map[Int, Option[PodAddress]]]     = ZIO.succeed(shards)
      }
    }

  class ShardManagerClientLive(
    sttp: SttpBackend[Task, ZioStreams with WebSockets],
    config: Config
  ) extends ShardManagerClient {
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
  }
}
