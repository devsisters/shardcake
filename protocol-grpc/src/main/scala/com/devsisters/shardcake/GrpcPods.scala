package com.devsisters.shardcake

import com.devsisters.shardcake.errors._
import com.devsisters.shardcake.interfaces.Pods
import com.devsisters.shardcake.interfaces.Pods.BinaryMessage
import com.devsisters.shardcake.protocol.Sharding._
import io.grpc.{ ClientInterceptors, ManagedChannel, ManagedChannelBuilder, Status, StatusException }
import proteus.client.ZioClientBackend
import zio._
import zio.stream.ZStream

import java.util.concurrent.TimeUnit

class GrpcPods(
  config: GrpcConfig,
  connections: Ref.Synchronized[Map[PodAddress, (PodClient, Fiber[Throwable, Nothing])]]
) extends Pods {

  private def getConnection(pod: PodAddress): Task[PodClient] =
    // optimize happy path and only get first
    connections.get.flatMap(_.get(pod) match {
      case Some((client, _)) => ZIO.succeed(client)
      case None              =>
        // then do modify in the case it doesn't already exist
        connections.modifyZIO { map =>
          map.get(pod) match {
            case Some((client, _)) => ZIO.succeed((client, map))
            case None              =>
              val builder = {
                config.executor match {
                  case Some(executor) =>
                    ManagedChannelBuilder
                      .forAddress(pod.host, pod.port)
                      .executor(executor)
                      .maxInboundMessageSize(config.maxInboundMessageSize)
                      .usePlaintext()
                  case None           =>
                    ManagedChannelBuilder
                      .forAddress(pod.host, pod.port)
                      .maxInboundMessageSize(config.maxInboundMessageSize)
                      .usePlaintext()
                }
              }

              val acquireChannel: RIO[Scope, ManagedChannel] =
                ZIO.acquireRelease(ZIO.attempt(builder.build())) { channel =>
                  ZIO.attemptBlocking {
                    channel.shutdown()
                    channel.awaitTermination(config.shutdownTimeout.toMillis, TimeUnit.MILLISECONDS)
                    channel.shutdownNow(): Unit
                  }.ignore
                }

              // create a fiber that never ends and keeps the connection alive
              for {
                _       <- ZIO.logDebug(s"Opening connection to pod $pod")
                promise <- Promise.make[Nothing, PodClient]
                fiber   <- ZIO
                             .scoped[Any] {
                               acquireChannel.flatMap { rawChannel =>
                                 val channel =
                                   if (config.clientInterceptors.isEmpty) rawChannel
                                   else ClientInterceptors.intercept(rawChannel, config.clientInterceptors*)
                                 val backend = ZioClientBackend(channel, config.streamingPrefetch)
                                 val client  = PodClient.from(rawChannel, backend)
                                 promise.succeed(client) *> ZIO.never
                               }
                             }
                             .ensuring(
                               connections.update(_ - pod) *> ZIO.logDebug(s"Closed connection to pod $pod")
                             )
                             .forkDaemon
                client  <- promise.await
              } yield (client, map.updated(pod, (client, fiber)))
          }
        }
    })

  private def mapClientError(pod: PodAddress, entityId: String, isStream: Boolean)(t: Throwable): Throwable =
    t match {
      case ex: StatusException if ex.getStatus.getCode == Status.Code.RESOURCE_EXHAUSTED =>
        // entity is not managed by this pod, wait and retry (assignments will be updated)
        EntityNotManagedByThisPod(entityId)
      case ex: StatusException if ex.getStatus.getCode == Status.Code.UNAVAILABLE        => PodUnavailable(pod)
      case ex: StatusException if ex.getStatus.getCode == Status.Code.CANCELLED          =>
        if (isStream) StreamCancelled else PodUnavailable(pod)
      case other                                                                         => other
    }

  private def toSendRequest(message: BinaryMessage): SendRequest =
    SendRequest(message.entityId, message.entityType, message.body, message.replyId)

  def assignShards(pod: PodAddress, shards: Set[ShardId]): Task[Unit] =
    getConnection(pod).flatMap(_.assignShards(AssignShardsRequest(shards.toList)).unit)

  def unassignShards(pod: PodAddress, shards: Set[ShardId]): Task[Unit] =
    getConnection(pod).flatMap(_.unassignShards(UnassignShardsRequest(shards.toList)).unit)

  def ping(pod: PodAddress): Task[Unit] =
    getConnection(pod).flatMap(_.pingShards(PingShardsRequest()).unit)

  def sendMessage(pod: PodAddress, message: BinaryMessage): Task[Option[Array[Byte]]] =
    getConnection(pod).flatMap { conn =>
      conn
        .send(toSendRequest(message))
        .mapBoth(
          mapClientError(pod, message.entityId, isStream = false),
          res => if (res.body.isEmpty) None else Some(res.body)
        )
    }

  def sendStream(
    pod: PodAddress,
    entityId: String,
    messages: ZStream[Any, Throwable, BinaryMessage]
  ): Task[Option[Array[Byte]]] =
    getConnection(pod).flatMap { conn =>
      conn
        .sendStream(messages.mapBoth(Status.INTERNAL.withCause(_).asException(), toSendRequest))
        .mapBoth(
          mapClientError(pod, entityId, isStream = true),
          res => if (res.body.isEmpty) None else Some(res.body)
        )
    }

  def sendMessageAndReceiveStream(pod: PodAddress, message: BinaryMessage): ZStream[Any, Throwable, Array[Byte]] =
    ZStream
      .fromZIO(getConnection(pod))
      .flatMap(
        _.sendAndReceiveStream(toSendRequest(message))
          .mapBoth(mapClientError(pod, message.entityId, isStream = true), _.body)
      )

  def sendStreamAndReceiveStream(
    pod: PodAddress,
    entityId: String,
    messages: ZStream[Any, Throwable, BinaryMessage]
  ): ZStream[Any, Throwable, Array[Byte]] =
    ZStream
      .fromZIO(getConnection(pod))
      .flatMap(
        _.sendStreamAndReceiveStream(
          messages.mapBoth(Status.INTERNAL.withCause(_).asException(), toSendRequest)
        ).mapBoth(mapClientError(pod, entityId, isStream = true), _.body)
      )
}

object GrpcPods {

  /**
   * A layer that creates an instance of Pods that communicates using the gRPC protocol.
   */
  val live: ZLayer[GrpcConfig, Throwable, Pods] =
    ZLayer.scoped {
      for {
        config      <- ZIO.service[GrpcConfig]
        connections <- Ref.Synchronized
                         .make(Map.empty[PodAddress, (PodClient, Fiber[Throwable, Nothing])])
                         .withFinalizer(
                           // stop all connection fibers on release
                           _.get.flatMap(conns => ZIO.foreachDiscard(conns) { case (_, (_, fiber)) => fiber.interrupt })
                         )
      } yield new GrpcPods(config, connections)
    }
}
