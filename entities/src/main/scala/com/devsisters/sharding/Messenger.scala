package com.devsisters.sharding

import com.devsisters.sharding.Messenger.Address
import zio.{ Task, UIO }

trait Messenger[-Msg] {
  def tell(entityId: String)(msg: Msg): UIO[Unit]
  def ask[Res](entityId: String)(msg: Address[Res] => Msg): Task[Res]
}

object Messenger {
  case class Address[-R](id: String)
}
