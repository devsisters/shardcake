package com.devsisters.shardcake

import zio.{ Task, UIO }

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
