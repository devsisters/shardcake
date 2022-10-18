package com.devsisters.shardcake

import zio.{ URIO, ZIO }

/**
 * A metadata object that allows sending a response back to the sender
 */
case class Replier[-R](id: String) { self =>
  def reply(reply: R): URIO[Sharding, Unit] = ZIO.serviceWithZIO[Sharding](_.reply(reply, self))
}
