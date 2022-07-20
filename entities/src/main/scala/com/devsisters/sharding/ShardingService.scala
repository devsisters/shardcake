package com.devsisters.sharding

import com.devsisters.sharding.interfaces.Serialization
import com.devsisters.sharding.protobuf.sharding._
import com.devsisters.sharding.protobuf.sharding.ZioSharding.ZShardingService
import com.google.protobuf.ByteString
import io.grpc.{ Status, StatusException, StatusRuntimeException }
import zio._

trait ShardingService extends ZShardingService[Sharding with Serialization, Any] {
  def assignShards(request: AssignShardsRequest): ZIO[Sharding, Status, AssignShardsResponse] =
    ZIO.serviceWithZIO[Sharding](_.assign(request.shards.toSet)).as(AssignShardsResponse())

  def unassignShards(request: UnassignShardsRequest): ZIO[Sharding, Status, UnassignShardsResponse] =
    ZIO.serviceWithZIO[Sharding](_.unassign(request.shards.toSet)).as(UnassignShardsResponse())

  def send(request: SendRequest): ZIO[Sharding with Serialization, Status, SendResponse] =
    ZIO
      .serviceWithZIO[Serialization](_.decode(request.body.toByteArray))
      .flatMap(msg => ZIO.serviceWithZIO[Sharding](_.hub.sendToLocalEntity(request.entityId, request.entityType, msg)))
      .flatMap {
        case None      => ZIO.succeed(ByteString.EMPTY)
        case Some(res) => ZIO.serviceWithZIO[Serialization](_.encode(res).map(ByteString.copyFrom))
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
