package com.devsisters.shardcake

import com.devsisters.shardcake.Messenger.Replier
import zio.{ Task, UIO, URIO, ZIO }

trait Messenger[-Msg] {
  def sendDiscard(entityId: String)(msg: Msg): UIO[Unit]
  def send[Res](entityId: String)(msg: Replier[Res] => Msg): Task[Res]
}

object Messenger {
  case class Replier[-R](id: String) { self =>
    def reply(reply: R): URIO[Sharding, Unit] = ZIO.serviceWithZIO[Sharding](_.reply(reply, self))
  }
}
