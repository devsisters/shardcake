package com.devsisters.shardcake

import com.devsisters.shardcake.interfaces.Serialization
import zio.UIO

import scala.util.Try

/**
 * An interface to communicate with a remote broadcast receiver
 * @tparam Msg the type of message that can be sent to this broadcast receiver
 */
trait Broadcaster[-Msg] {

  /**
   * Broadcast a message without waiting for a response (fire and forget)
   */
  def broadcastDiscard(topic: String)(msg: Msg): UIO[Unit]

  /**
   * Broadcast a message and wait for a response from each consumer
   */
  def broadcast[Res: Serialization](topic: String)(msg: Replier[Res] => Msg): UIO[Map[PodAddress, Try[Res]]]
}
