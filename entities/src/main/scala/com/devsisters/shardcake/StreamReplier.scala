package com.devsisters.shardcake

import zio.stream.ZStream
import zio.{ URIO, ZIO }

/**
 * A metadata object that allows sending a stream of responses back to the sender
 */
final case class StreamReplier[-R](id: String) { self =>
  def replyStream(replies: ZStream[Any, Nothing, R]): URIO[Sharding, Unit] =
    ZIO.serviceWithZIO[Sharding](_.replyStream(replies, self))
}
