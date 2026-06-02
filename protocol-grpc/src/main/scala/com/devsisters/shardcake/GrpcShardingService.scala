package com.devsisters.shardcake

import com.devsisters.shardcake.errors.EntityNotManagedByThisPod
import com.devsisters.shardcake.interfaces.Pods.BinaryMessage
import com.devsisters.shardcake.protocol.Sharding
import com.devsisters.shardcake.protocol.Sharding._
import io.grpc._
import io.grpc.protobuf.services.ProtoReflectionServiceV1
import proteus.server.{ GrpcContext, ServerService, ZioServerBackend }
import zio.stream.ZStream
import zio.{ Config => _, _ }

import java.util.concurrent.TimeUnit

abstract class GrpcShardingService(sharding: Sharding, timeout: Duration) {
  def assignShards(request: AssignShardsRequest): ZIO[Any, StatusException, AssignShardsResponse] =
    sharding.assign(request.shards.toSet).as(AssignShardsResponse())

  def unassignShards(request: UnassignShardsRequest): ZIO[Any, StatusException, UnassignShardsResponse] =
    sharding.unassign(request.shards.toSet).as(UnassignShardsResponse())

  def send(request: SendRequest): ZIO[Any, StatusException, SendResponse] =
    sharding
      .sendToLocalEntity(GrpcShardingService.toBinary(request))
      .map(GrpcShardingService.toSendResponse)
      .mapError(GrpcShardingService.mapErrorToStatus)
      .timeoutFail(GrpcShardingService.timeoutException)(timeout)

  def sendStream(
    requests: ZStream[Any, StatusException, SendRequest]
  ): ZIO[Any, StatusException, SendResponse] =
    sharding
      .sendStreamToLocalEntity(requests.map(GrpcShardingService.toBinary))
      .map(GrpcShardingService.toSendResponse)
      .mapError(GrpcShardingService.mapErrorToStatus)

  def sendAndReceiveStream(request: SendRequest): ZStream[Any, StatusException, SendResponse] =
    sharding
      .sendToLocalEntityAndReceiveStream(GrpcShardingService.toBinary(request))
      .map(SendResponse(_))
      .mapError(GrpcShardingService.mapErrorToStatus)

  def sendStreamAndReceiveStream(
    requests: ZStream[Any, StatusException, SendRequest]
  ): ZStream[Any, StatusException, SendResponse] =
    sharding
      .sendStreamToLocalEntityAndReceiveStream(requests.map(GrpcShardingService.toBinary))
      .map(SendResponse(_))
      .mapError(GrpcShardingService.mapErrorToStatus)

  def pingShards(request: PingShardsRequest): ZIO[Any, StatusException, PingShardsResponse] =
    ZIO.succeed(PingShardsResponse())
}

object GrpcShardingService {

  private[shardcake] val timeoutException: StatusException =
    Status.ABORTED.withDescription("Timeout while handling sharding send grpc").asException()

  private val emptySendResponse: SendResponse = SendResponse(Array.emptyByteArray)

  private[shardcake] def toBinary(req: SendRequest): BinaryMessage =
    BinaryMessage(req.entityId, req.entityType, req.body, req.replyId)

  private[shardcake] def toSendResponse(body: Option[Array[Byte]]): SendResponse =
    body.fold(emptySendResponse)(SendResponse(_))

  private[shardcake] val mapErrorToStatus: Throwable => StatusException = {
    case e: StatusException           => e
    case e: StatusRuntimeException    => e.getStatus.asException()
    case e: EntityNotManagedByThisPod => Status.RESOURCE_EXHAUSTED.withCause(e).asException()
    case e                            => Status.INTERNAL.withCause(e).withDescription(e.getMessage).asException()
  }

  private def buildServiceDefinition(
    service: GrpcShardingService,
    backend: ZioServerBackend[Any, StatusException, GrpcContext]
  ): ServerServiceDefinition =
    ServerService(using backend)
      .rpc(Sharding.assignShards, service.assignShards)
      .rpc(Sharding.unassignShards, service.unassignShards)
      .rpc(Sharding.send, service.send)
      .rpc(Sharding.sendStream, service.sendStream)
      .rpc(Sharding.sendAndReceiveStream, service.sendAndReceiveStream)
      .rpc(Sharding.sendStreamAndReceiveStream, service.sendStreamAndReceiveStream)
      .rpc(Sharding.pingShards, service.pingShards)
      .build(Sharding.service)

  /**
   * A layer that creates a gRPC server that exposes the Pods API.
   */
  val live: ZLayer[Config with Sharding with GrpcConfig, Throwable, Unit] =
    ZLayer.scoped[Config with Sharding with GrpcConfig] {
      for {
        config           <- ZIO.service[Config]
        grpcConfig       <- ZIO.service[GrpcConfig]
        sharding         <- ZIO.service[Sharding]
        runtime          <- ZIO.runtime[Any]
        interceptor       = ShardingServerInterceptor.compose(grpcConfig.serverInterceptors)
        backend           = ZioServerBackend(interceptor, runtime, grpcConfig.streamingPrefetch)
        service           = new GrpcShardingService(sharding, config.sendTimeout) {}
        serviceDefinition = buildServiceDefinition(service, backend)
        baseBuilder       = grpcConfig.executor match {
                              case Some(executor) =>
                                ServerBuilder
                                  .forPort(config.shardingPort)
                                  .executor(executor)
                              case None           =>
                                ServerBuilder.forPort(config.shardingPort)
                            }
        builder           = baseBuilder
                              .maxInboundMessageSize(grpcConfig.maxInboundMessageSize)
                              .addService(serviceDefinition)
                              .addService(ProtoReflectionServiceV1.newInstance())
        server: Server    = builder.build()
        _                <- ZIO.acquireRelease(ZIO.attempt(server.start()))(server =>
                              ZIO.attemptBlocking {
                                server.shutdown()
                                server.awaitTermination(grpcConfig.shutdownTimeout.toMillis, TimeUnit.MILLISECONDS)
                                server.shutdownNow()
                              }.ignore
                            )
      } yield ()
    }
}
