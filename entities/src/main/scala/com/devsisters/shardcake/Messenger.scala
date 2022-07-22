package com.devsisters.shardcake

import com.devsisters.shardcake.Messenger.Address
import zio.{ Task, UIO }

trait Messenger[-Msg] {
  def tell(entityId: String)(msg: Msg): UIO[Unit]
  def ask[Res](entityId: String)(msg: Address[Res] => Msg): Task[Res]
}

object Messenger {
  case class Address[-R](id: String)
}
