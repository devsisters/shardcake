package com.devsisters.shardcake

import caliban.client.Operations.IsOperation
import caliban.client.SelectionBuilder
import com.devsisters.shardcake.internal.GraphQLClient
import com.devsisters.shardcake.internal.GraphQLClient.{ PodAddressInput, RoleInput }
import sttp.client4.Backend
import sttp.client4.httpclient.zio.HttpClientZioBackend
import zio.{ Config => _, _ }

/**
 * An interface to communicate with the Shard Manager API
 */
trait ShardManagerClient {
  def register(podAddress: PodAddress, role: Role): Task[Unit]
  def unregister(podAddress: PodAddress): Task[Unit]
  def notifyUnhealthyPod(podAddress: PodAddress): Task[Unit]
  def getAssignments(role: Role): Task[Map[ShardId, Option[PodAddress]]]
}

object ShardManagerClient {

  /**
   * A layer that returns a client for the Shard Manager API.
   * It requires an sttp backend. If you don't want to use your own backend, simply use `liveWithSttp`.
   */
  val live: ZLayer[Config with Backend[Task], Nothing, ShardManagerClientLive] =
    ZLayer {
      for {
        sttpClient <- ZIO.service[Backend[Task]]
        config     <- ZIO.service[Config]
      } yield new ShardManagerClientLive(sttpClient, config)
    }

  /**
   * A layer that returns a client for the Shard Manager API.
   * It contains its own sttp backend so you don't need to provide one.
   */
  val liveWithSttp: ZLayer[Config, Throwable, ShardManagerClient] =
    HttpClientZioBackend.layer() >>> live

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
        def register(podAddress: PodAddress, role: Role): Task[Unit]           = ZIO.unit
        def unregister(podAddress: PodAddress): Task[Unit]                     = ZIO.unit
        def notifyUnhealthyPod(podAddress: PodAddress): Task[Unit]             = ZIO.unit
        def getAssignments(role: Role): Task[Map[ShardId, Option[PodAddress]]] = ZIO.succeed(shards)
      }
    }

  class ShardManagerClientLive(sttp: Backend[Task], config: Config) extends ShardManagerClient {
    private def send[Origin: IsOperation, A](query: SelectionBuilder[Origin, A]): Task[A] =
      sttp.send(query.toRequest(config.shardManagerUri)).map(_.body).absolve

    def register(podAddress: PodAddress, role: Role): Task[Unit] =
      send(
        GraphQLClient.Mutations.register(
          PodAddressInput(podAddress.host, podAddress.port),
          config.serverVersion,
          RoleInput(role.name)
        )
      ).unit

    def unregister(podAddress: PodAddress): Task[Unit] =
      send(GraphQLClient.Mutations.unregister(PodAddressInput(podAddress.host, podAddress.port))).unit

    def notifyUnhealthyPod(podAddress: PodAddress): Task[Unit] =
      ZIO.logWarning(s"Notifying Shard Manager about unhealthy pod $podAddress") *>
        send(GraphQLClient.Mutations.notifyUnhealthyPod(PodAddressInput(podAddress.host, podAddress.port)))

    def getAssignments(role: Role): Task[Map[ShardId, Option[PodAddress]]] =
      send(
        GraphQLClient.Queries
          .getAssignments(role.name)(
            GraphQLClient.Assignment.shardId ~ GraphQLClient.Assignment
              .pod((GraphQLClient.PodAddress.host ~ GraphQLClient.PodAddress.port).map { case (host, port) =>
                PodAddress(host, port)
              })
          )
          .map(_.toMap)
      )
  }
}
