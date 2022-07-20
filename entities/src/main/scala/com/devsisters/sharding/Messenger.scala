package com.devsisters.sharding

import com.devsisters.sharding.Messenger.Address
import zio.{ Task, UIO }

trait Messenger[-Msg] {
  def tell(entityId: String)(msg: Msg): UIO[Unit]
  def ask[Res](entityId: String)(msg: Address[Res] => Msg): Task[Res]
  def reply[Reply](reply: Reply, replyTo: Address[Reply]): UIO[Unit]
}

object Messenger {
  case class Address[-R](id: String)
}
