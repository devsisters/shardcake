package com.devsisters.shardcake

import com.devsisters.shardcake.errors.PodUnavailable
import zio._
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
  def sendStream[Res](entityId: String)(msg: StreamReplier[Res] => Msg): Task[ZStream[Any, Throwable, Res]]

  /**
   * Send a message and receive a stream of responses of type `Res` and restart the stream when the remote entity is
   * rebalanced.
   *
   * To do so, we need a "cursor" so the stream of responses can be restarted where it ended before the rebalance. That
   * is, the first message sent to the remote entity contains the given initial cursor value and we extract an updated
   * cursor from the responses so that when the remote entity is rebalanced, a new message can be sent with the last
   * cursor we've seen in the previous stream of responses.
   */
  def sendStreamAutoRestart[Cursor, Res](entityId: String, cursor: Cursor)(msg: (Cursor, StreamReplier[Res]) => Msg)(
    updateCursor: (Cursor, Res) => Cursor
  ): ZStream[Any, Throwable, Res] =
    ZStream
      .unwrap(sendStream(entityId)(msg(cursor, _)))
      .either
      .mapAccum(cursor) {
        case (c, Right(res)) => updateCursor(c, res) -> Right(res)
        case (c, Left(err))  => (c, Left(c -> err))
      }
      .flatMap {
        case Right(res)                                => ZStream.succeed(res)
        case Left((lastSeenCursor, PodUnavailable(_))) =>
          ZStream.execute(ZIO.sleep(200.millis)) ++
            sendStreamAutoRestart(entityId, lastSeenCursor)(msg)(updateCursor)
        case Left((_, err))                            => ZStream.fail(err)
      }
}
