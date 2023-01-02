package com.devsisters.shardcake

import com.devsisters.shardcake.errors.EntityNotManagedByThisPod
import com.devsisters.shardcake.interfaces.Pods.BinaryMessage
import com.devsisters.shardcake.protobuf.sharding.ZioSharding.ZShardingService
import com.devsisters.shardcake.protobuf.sharding._
import com.google.protobuf.ByteString
import io.grpc.protobuf.services.ProtoReflectionService
import io.grpc.{ ServerBuilder, Status, StatusException, StatusRuntimeException }
import scalapb.zio_grpc.{ ServerLayer, ServiceList }
import zio._
import zio.stream.ZStream

abstract class GrpcShardingService(timeout: Duration) extends ZShardingService[Sharding, Any] {
  def assignShards(request: AssignShardsRequest): ZIO[Sharding, Status, AssignShardsResponse] =
    ZIO.serviceWithZIO[Sharding](_.assign(request.shards.toSet)).as(AssignShardsResponse())

  def unassignShards(request: UnassignShardsRequest): ZIO[Sharding, Status, UnassignShardsResponse] =
    ZIO.serviceWithZIO[Sharding](_.unassign(request.shards.toSet)).as(UnassignShardsResponse())

  def send(request: SendRequest): ZIO[Sharding, Status, SendResponse] =
    ZIO
      .serviceWithZIO[Sharding](
        _.sendToLocalEntitySingleReply(
          BinaryMessage(request.entityId, request.entityType, request.body.toByteArray, request.replyId)
        )
      )
      .map {
        case None      => ByteString.EMPTY
        case Some(res) => ByteString.copyFrom(res)
      }
      .mapBoth(mapErrorToStatusWithInternalDetails, SendResponse(_))
      .timeoutFail(Status.ABORTED.withDescription("Timeout while handling sharding send grpc"))(timeout)

  def sendStream(request: SendRequest): ZStream[Sharding, Status, SendResponse] =
    ZStream
      .serviceWithStream[Sharding](
        _.sendToLocalEntityStreamingReply(
          BinaryMessage(request.entityId, request.entityType, request.body.toByteArray, request.replyId)
        )
      )
      .map(ByteString.copyFrom)
      .mapBoth(mapErrorToStatusWithInternalDetails, SendResponse(_))

  def pingShards(request: PingShardsRequest): ZIO[Sharding, Status, PingShardsResponse] =
    ZIO.succeed(PingShardsResponse())

  private def mapErrorToStatusWithInternalDetails: Function[Throwable, Status] = {
    case e: StatusException           => e.getStatus
    case e: StatusRuntimeException    => e.getStatus
    case e: EntityNotManagedByThisPod => Status.RESOURCE_EXHAUSTED.withCause(e)
    case e                            => Status.INTERNAL.withCause(e).withDescription(e.getMessage)
  }
}

object GrpcShardingService {

  /**
   * A layer that creates a gRPC server that exposes the Pods API.
   */
  val live: ZLayer[Config with Sharding, Throwable, Unit] =
    ZLayer.service[Config].flatMap { config =>
      val builder  = ServerBuilder.forPort(config.get.shardingPort).addService(ProtoReflectionService.newInstance())
      val services = ServiceList.add(new GrpcShardingService(config.get.sendTimeout) {})
      ServerLayer.fromServiceList(builder, services).unit
    }
}
