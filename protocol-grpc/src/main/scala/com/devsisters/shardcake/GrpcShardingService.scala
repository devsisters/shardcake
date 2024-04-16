package com.devsisters.shardcake

import com.devsisters.shardcake.errors.EntityNotManagedByThisPod
import com.devsisters.shardcake.interfaces.Pods.BinaryMessage
import com.devsisters.shardcake.protobuf.sharding.ZioSharding.ShardingService
import com.devsisters.shardcake.protobuf.sharding._
import com.google.protobuf.ByteString
import io.grpc._
import io.grpc.protobuf.services.ProtoReflectionService
import scalapb.zio_grpc.ServiceList
import zio.stream.ZStream
import zio.{ Config => _, _ }

import java.util.concurrent.TimeUnit

abstract class GrpcShardingService(sharding: Sharding, timeout: Duration) extends ShardingService {
  def assignShards(request: AssignShardsRequest): ZIO[Any, StatusException, AssignShardsResponse] =
    sharding.assign(request.shards.toSet).as(AssignShardsResponse())

  def unassignShards(request: UnassignShardsRequest): ZIO[Any, StatusException, UnassignShardsResponse] =
    sharding.unassign(request.shards.toSet).as(UnassignShardsResponse())

  def send(request: SendRequest): ZIO[Any, StatusException, SendResponse] =
    sharding
      .sendToLocalEntity(
        BinaryMessage(request.entityId, request.entityType, request.body.toByteArray, request.replyId)
      )
      .map {
        case None      => ByteString.EMPTY
        case Some(res) => ByteString.copyFrom(res)
      }
      .mapBoth(mapErrorToStatusWithInternalDetails, SendResponse(_))
      .timeoutFail(Status.ABORTED.withDescription("Timeout while handling sharding send grpc").asException())(timeout)

  def sendAndReceiveStream(request: SendRequest): ZStream[Any, StatusException, SendResponse] =
    sharding
      .sendToLocalEntityAndReceiveStream(
        BinaryMessage(request.entityId, request.entityType, request.body.toByteArray, request.replyId)
      )
      .mapBoth(mapErrorToStatusWithInternalDetails, bytes => SendResponse(ByteString.copyFrom(bytes)))

  def sendStream(
    requests: ZStream[Any, StatusException, SendRequest]
  ): ZIO[Any, StatusException, SendResponse] =
    sharding
      .sendStreamToLocalEntity(
        requests.map(request =>
          BinaryMessage(request.entityId, request.entityType, request.body.toByteArray, request.replyId)
        )
      )
      .map {
        case None      => ByteString.EMPTY
        case Some(res) => ByteString.copyFrom(res)
      }
      .mapBoth(mapErrorToStatusWithInternalDetails, SendResponse(_))

  def sendStreamAndReceiveStream(
    requests: ZStream[Any, StatusException, SendRequest]
  ): ZStream[Any, StatusException, SendResponse] =
    sharding
      .sendStreamToLocalEntityAndReceiveStream(
        requests.map(request =>
          BinaryMessage(request.entityId, request.entityType, request.body.toByteArray, request.replyId)
        )
      )
      .mapBoth(mapErrorToStatusWithInternalDetails, bytes => SendResponse(ByteString.copyFrom(bytes)))

  def pingShards(request: PingShardsRequest): ZIO[Any, StatusException, PingShardsResponse] =
    ZIO.succeed(PingShardsResponse())

  private def mapErrorToStatusWithInternalDetails: Function[Throwable, StatusException] = {
    case e: StatusException           => e
    case e: StatusRuntimeException    => e.getStatus.asException()
    case e: EntityNotManagedByThisPod => Status.RESOURCE_EXHAUSTED.withCause(e).asException()
    case e                            => Status.INTERNAL.withCause(e).withDescription(e.getMessage).asException()
  }
}

object GrpcShardingService {

  /**
   * A layer that creates a gRPC server that exposes the Pods API.
   */
  val live: ZLayer[Config with Sharding with GrpcConfig, Throwable, Unit] =
    ZLayer.scoped[Config with Sharding with GrpcConfig] {
      for {
        config        <- ZIO.service[Config]
        grpcConfig    <- ZIO.service[GrpcConfig]
        sharding      <- ZIO.service[Sharding]
        builder        = grpcConfig.executor match {
                           case Some(executor) =>
                             ServerBuilder
                               .forPort(config.shardingPort)
                               .executor(executor)
                           case None           =>
                             ServerBuilder.forPort(config.shardingPort)
                         }
        services      <- ServiceList.add(new GrpcShardingService(sharding, config.sendTimeout) {}).bindAll
        server: Server = services
                           .foldLeft(builder) { case (builder0, service) => builder0.addService(service) }
                           .addService(ProtoReflectionService.newInstance())
                           .build()
        _             <- ZIO.acquireRelease(ZIO.attempt(server.start()))(server =>
                           ZIO.attemptBlocking {
                             server.shutdown()
                             server.awaitTermination(grpcConfig.shutdownTimeout.toMillis, TimeUnit.MILLISECONDS)
                             server.shutdownNow()
                           }.ignore
                         )
      } yield ()
    }
}
