package com.devsisters.shardcake

import caliban.client.Operations.IsOperation
import caliban.client.SelectionBuilder
import com.devsisters.shardcake.internal.GraphQLClient
import com.devsisters.shardcake.internal.GraphQLClient.PodAddressInput
import sttp.client3.SttpBackend
import sttp.client3.asynchttpclient.zio.AsyncHttpClientZioBackend
import zio.{ Config => _, _ }

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
  val live: ZLayer[Config with SttpBackend[Task, Any], Nothing, ShardManagerClientLive] =
    ZLayer {
      for {
        sttpClient <- ZIO.service[SttpBackend[Task, Any]]
        config     <- ZIO.service[Config]
      } yield new ShardManagerClientLive(sttpClient, config)
    }

  /**
   * A layer that returns a client for the Shard Manager API.
   * It contains its own sttp backend so you don't need to provide one.
   */
  val liveWithSttp: ZLayer[Config, Throwable, ShardManagerClient] =
    AsyncHttpClientZioBackend.layer() >>> live

  /**
   * A layer that mocks the Shard Manager, useful for testing with a single pod.
   */
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

  class ShardManagerClientLive(sttp: SttpBackend[Task, Any], config: Config) extends ShardManagerClient {
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
