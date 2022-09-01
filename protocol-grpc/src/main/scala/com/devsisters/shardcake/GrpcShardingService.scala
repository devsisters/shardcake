package com.devsisters.shardcake

import com.devsisters.shardcake.errors.EntityNotManagedByThisPod
import com.devsisters.shardcake.interfaces.Pods.BinaryMessage
import com.devsisters.shardcake.protobuf.sharding.ZioSharding.ZShardingService
import com.devsisters.shardcake.protobuf.sharding._
import com.google.protobuf.ByteString
import io.grpc.protobuf.services.ProtoReflectionService
import io.grpc.{ ServerBuilder, Status, StatusException, StatusRuntimeException }
import scalapb.zio_grpc.{ Server, ServerLayer, ServiceList }
import zio._
import zio.clock.Clock
import zio.duration._

abstract class GrpcShardingService(timeout: Duration) extends ZShardingService[Has[Sharding] with Clock, Any] {
  def assignShards(request: AssignShardsRequest): ZIO[Has[Sharding], Status, AssignShardsResponse] =
    ZIO.serviceWith[Sharding](_.assign(request.shards.toSet)).as(AssignShardsResponse())

  def unassignShards(request: UnassignShardsRequest): ZIO[Has[Sharding], Status, UnassignShardsResponse] =
    ZIO.serviceWith[Sharding](_.unassign(request.shards.toSet)).as(UnassignShardsResponse())

  def send(request: SendRequest): ZIO[Has[Sharding] with Clock, Status, SendResponse] =
    ZIO
      .serviceWith[Sharding](
        _.sendToLocalEntity(
          BinaryMessage(request.entityId, request.entityType, request.body.toByteArray, request.replyId)
        )
      )
      .map {
        case None      => ByteString.EMPTY
        case Some(res) => ByteString.copyFrom(res)
      }
      .mapBoth(mapErrorToStatusWithInternalDetails, SendResponse(_))
      .timeoutFail(Status.ABORTED.withDescription("Timeout while handling sharding send grpc"))(timeout)

  def pingShards(request: PingShardsRequest): ZIO[Has[Sharding], Status, PingShardsResponse] =
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
  val live: ZLayer[Has[Config] with Has[Sharding] with Clock, Throwable, Server] =
    ZLayer.service[Config].flatMap { config =>
      val builder  = ServerBuilder.forPort(config.get.shardingPort).addService(ProtoReflectionService.newInstance())
      val services = ServiceList.add(new GrpcShardingService(config.get.sendTimeout) {})
      ServerLayer.fromServiceList(builder, services)
    }
}
