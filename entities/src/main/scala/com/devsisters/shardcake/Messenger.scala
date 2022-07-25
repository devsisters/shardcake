package com.devsisters.shardcake

import com.devsisters.shardcake.Messenger.Replier
import zio.{ Task, UIO, URIO, ZIO }

/**
 * An interface to communicate with a remote entity
 * @tparam Msg the type of message that can be sent to this entity type
 */
trait Messenger[-Msg] {

  /**
   * Send a message without waiting for a response (fire and forget)
   */
  def sendDiscard(entityId: String)(msg: Msg): UIO[Unit]

  /**
   * Send a message and wait for a response of type `Res`
   */
  def send[Res](entityId: String)(msg: Replier[Res] => Msg): Task[Res]
}

object Messenger {

  /**
   * A metadata object that allows sending a response back to the sender
   */
  case class Replier[-R](id: String) { self =>
    def reply(reply: R): URIO[Sharding, Unit] = ZIO.serviceWithZIO[Sharding](_.reply(reply, self))
  }
}
