package com.devsisters.sharding.internal

import com.devsisters.sharding.ShardId
import zio.Promise

private[sharding] sealed trait ShardEvent

object ShardEvent {
  case class NewMessage(body: Any, entityId: String, promise: Promise[Throwable, Option[Any]]) extends ShardEvent
  case class TerminateShards(shards: Set[ShardId], promise: Promise[Nothing, Unit])            extends ShardEvent
  case class Shutdown(promise: Promise[Nothing, Unit])                                         extends ShardEvent
}
