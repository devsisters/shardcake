package com.devsisters.shardcake

import com.devsisters.shardcake.Messenger.Replier
import zio.{ Task, UIO, URIO, ZIO }

/**
 * An interface to communicate with a remote broadcast receiver
 * @tparam Msg the type of message that can be sent to this broadcast receiver
 */
trait Broadcaster[-Msg] {

  /**
   * Broadcast a message without waiting for a response (fire and forget)
   */
  def broadcast(topic: String)(msg: Msg): UIO[Unit]
}
