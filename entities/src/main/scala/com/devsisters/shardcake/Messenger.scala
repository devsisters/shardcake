package com.devsisters.shardcake

import zio.Task
import zio.stream.ZStream

/**
 * An interface to communicate with a remote entity
 * @tparam Msg the type of message that can be sent to this entity type
 */
trait Messenger[-Msg] {

  /**
   * Send a message without waiting for a response (fire and forget)
   */
  def sendDiscard(entityId: String)(msg: Msg): Task[Unit]

  /**
   * Send a message and wait for a response of type `Res`
   */
  def send[Res](entityId: String)(msg: Replier[Res] => Msg): Task[Res]

  /**
   * Send a message and receive a stream of responses of type `Res`
   */
  def sendStream[Res](entityId: String)(msg: Replier[Res] => Msg): Task[ZStream[Any, Throwable, Res]]
}
