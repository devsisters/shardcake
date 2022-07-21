package com.devsisters.sharding

import com.devsisters.sharding.errors.EntityNotManagedByThisPod
import com.devsisters.sharding.interfaces.Pods.BinaryMessage
import com.devsisters.sharding.protobuf.sharding.ZioSharding.ZShardingService
import com.devsisters.sharding.protobuf.sharding._
import com.google.protobuf.ByteString
import io.grpc.protobuf.services.ProtoReflectionService
import io.grpc.{ ServerBuilder, Status, StatusException, StatusRuntimeException }
import scalapb.zio_grpc.{ ServerLayer, ServiceList }
import zio._

trait GrpcShardingService extends ZShardingService[Sharding, Any] {
  def assignShards(request: AssignShardsRequest): ZIO[Sharding, Status, AssignShardsResponse] =
    ZIO.serviceWithZIO[Sharding](_.assign(request.shards.toSet)).as(AssignShardsResponse())

  def unassignShards(request: UnassignShardsRequest): ZIO[Sharding, Status, UnassignShardsResponse] =
    ZIO.serviceWithZIO[Sharding](_.unassign(request.shards.toSet)).as(UnassignShardsResponse())

  def send(request: SendRequest): ZIO[Sharding, Status, SendResponse] =
    ZIO
      .serviceWithZIO[Sharding](
        _.sendToLocalEntity(
          BinaryMessage(request.entityId, request.entityType, request.body.toByteArray, request.replyId)
        )
      )
      .map {
        case None      => ByteString.EMPTY
        case Some(res) => ByteString.copyFrom(res)
      }
      .mapBoth(mapErrorToStatusWithInternalDetails, SendResponse(_))
      .timeoutFail(Status.ABORTED.withDescription("Timeout while handling sharding send grpc"))(10 seconds)

  def pingShards(request: PingShardsRequest): ZIO[Sharding, Status, PingShardsResponse] =
    ZIO.succeed(PingShardsResponse())

  protected def mapErrorToStatusWithInternalDetails: Function[Throwable, Status] = {
    case e: StatusException           => e.getStatus
    case e: StatusRuntimeException    => e.getStatus
    case e: EntityNotManagedByThisPod => Status.RESOURCE_EXHAUSTED.withCause(e)
    case e                            => Status.INTERNAL.withCause(e).withDescription(e.getMessage)
  }
}

object GrpcShardingService {
  val live: ZLayer[Config with Sharding, Throwable, Unit] =
    ZLayer.service[Config].flatMap { config =>
      val builder  = ServerBuilder.forPort(config.get.shardingPort).addService(ProtoReflectionService.newInstance())
      val services = ServiceList.add(new GrpcShardingService {})
      ServerLayer.fromServiceList(builder, services).unit
    }
}
