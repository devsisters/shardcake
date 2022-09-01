package com.devsisters.shardcake

import caliban.client.Operations.IsOperation
import caliban.client.SelectionBuilder
import com.devsisters.shardcake.interfaces.Logging
import com.devsisters.shardcake.internal.GraphQLClient
import com.devsisters.shardcake.internal.GraphQLClient.PodAddressInput
import sttp.client3.asynchttpclient.zio.{ AsyncHttpClientZioBackend, SttpClient }
import zio._

/**
 * An interface to communicate with the Shard Manager API
 */
trait ShardManagerClient {
  def register(podAddress: PodAddress): Task[Unit]
  def unregister(podAddress: PodAddress): Task[Unit]
  def notifyUnhealthyPod(podAddress: PodAddress): Task[Unit]
  def getAssignments: Task[Map[Int, Option[PodAddress]]]
}

object ShardManagerClient {

  /**
   * A layer that returns a client for the Shard Manager API.
   * It requires an sttp backend. If you don't want to use your own backend, simply use `liveWithSttp`.
   */
  val live: ZLayer[Has[Config] with SttpClient with Has[Logging], Nothing, Has[ShardManagerClient]] =
    (for {
      sttpClient <- ZIO.service[SttpClient.Service]
      config     <- ZIO.service[Config]
      logger     <- ZIO.service[Logging]
    } yield new ShardManagerClientLive(sttpClient, logger, config)).toLayer

  /**
   * A layer that returns a client for the Shard Manager API.
   * It contains its own sttp backend so you don't need to provide one.
   */
  val liveWithSttp: ZLayer[Has[Config] with Has[Logging], Throwable, Has[ShardManagerClient]] =
    ZLayer.requires[Has[Config]] ++ ZLayer.requires[Has[Logging]] ++ AsyncHttpClientZioBackend.layer() >>> live

  /**
   * A layer that mocks the Shard Manager, useful for testing with a single pod.
   */
  val local: ZLayer[Has[Config], Nothing, Has[ShardManagerClient]] =
    (for {
      config <- ZIO.service[Config]
      pod     = PodAddress(config.selfHost, config.shardingPort)
      shards  = (1 to config.numberOfShards).map(_ -> Some(pod)).toMap
    } yield new ShardManagerClient {
      def register(podAddress: PodAddress): Task[Unit]           = ZIO.unit
      def unregister(podAddress: PodAddress): Task[Unit]         = ZIO.unit
      def notifyUnhealthyPod(podAddress: PodAddress): Task[Unit] = ZIO.unit
      def getAssignments: Task[Map[Int, Option[PodAddress]]]     = ZIO.succeed(shards)
    }).toLayer

  class ShardManagerClientLive(
    sttp: SttpClient.Service,
    logger: Logging,
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
      logger.logWarning(s"Notifying Shard Manager about unhealthy pod $podAddress") *>
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
