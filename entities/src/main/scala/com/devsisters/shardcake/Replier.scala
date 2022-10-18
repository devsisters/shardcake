package com.devsisters.shardcake

import zio.{ Has, URIO, ZIO }

/**
 * A metadata object that allows sending a response back to the sender
 */
case class Replier[-R](id: String) { self =>
  def reply(reply: R): URIO[Has[Sharding], Unit] = ZIO.serviceWith[Sharding](_.reply(reply, self))
}
