package com.devsisters.shardcake

import com.devsisters.shardcake.errors.StreamCancelled
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
   * Send a message and receive a stream of responses of type `Res`.
   *
   * Note: The returned stream will fail with a `PodUnavailable` error if the remote entity is rebalanced while
   * streaming responses. See `sendStreamAutoRestart` for an alternative that will automatically restart the stream
   * in case of rebalance.
   */
  def sendAndReceiveStream[Res](entityId: String)(msg: StreamReplier[Res] => Msg): Task[ZStream[Any, Throwable, Res]]

  /**
   * Send a stream of messages.
   */
  def sendStream(entityId: String)(messages: ZStream[Any, Throwable, Msg]): Task[Unit]

  /**
   * Send a stream of messages and receive a stream of responses of type `Res`.
   */
  def sendStreamAndReceiveStream[Res](entityId: String)(
    messages: StreamReplier[Res] => ZStream[Any, Throwable, Msg]
  ): Task[ZStream[Any, Throwable, Res]]

  /**
   * Send a message and receive a stream of responses of type `Res` while restarting the stream when the remote entity
   * is rebalanced.
   *
   * To do so, we need a "cursor" so the stream of responses can be restarted where it ended before the rebalance. That
   * is, the first message sent to the remote entity contains the given initial cursor value and we extract an updated
   * cursor from the responses so that when the remote entity is rebalanced, a new message can be sent with the right
   * cursor according to what we've seen in the previous stream of responses.
   */
  def sendAndReceiveStreamAutoRestart[Cursor, Res](entityId: String, cursor: Cursor)(
    msg: (Cursor, StreamReplier[Res]) => Msg
  )(
    updateCursor: (Cursor, Res) => Cursor
  ): ZStream[Any, Throwable, Res] =
    ZStream
      .unwrap(sendAndReceiveStream[Res](entityId)(msg(cursor, _)))
      .either
      .mapAccum(cursor) {
        case (c, Right(res)) => updateCursor(c, res) -> Right(res)
        case (c, Left(err))  => (c, Left(c -> err))
      }
      .flatMap {
        case Right(res)                              => ZStream.succeed(res)
        case Left((lastSeenCursor, StreamCancelled)) =>
          ZStream.execute(ZIO.sleep(200.millis)) ++
            sendAndReceiveStreamAutoRestart(entityId, lastSeenCursor)(msg)(updateCursor)
        case Left((_, err))                          => ZStream.fail(err)
      }

  /**
   * Send a stream of messages and receive a stream of responses of type `Res` while restarting the stream when the
   * remote entity is rebalanced.
   *
   * To do so, we need a "cursor" so the stream of responses can be restarted where it ended before the rebalance. That
   * is, the first message sent to the remote entity contains the given initial cursor value and we extract an updated
   * cursor from the responses so that when the remote entity is rebalanced, a new message can be sent with the right
   * cursor according to what we've seen in the previous stream of responses.
   */
  def sendStreamAndReceiveStreamAutoRestart[Cursor, Res](entityId: String, cursor: Cursor)(
    msg: (Cursor, StreamReplier[Res]) => ZStream[Any, Throwable, Msg]
  )(
    updateCursor: (Cursor, Res) => Cursor
  ): ZStream[Any, Throwable, Res] =
    ZStream
      .unwrap(sendStreamAndReceiveStream[Res](entityId)(msg(cursor, _)))
      .either
      .mapAccum(cursor) {
        case (c, Right(res)) => updateCursor(c, res) -> Right(res)
        case (c, Left(err))  => (c, Left(c -> err))
      }
      .flatMap {
        case Right(res)                              => ZStream.succeed(res)
        case Left((lastSeenCursor, StreamCancelled)) =>
          ZStream.execute(ZIO.sleep(200.millis)) ++
            sendStreamAndReceiveStreamAutoRestart(entityId, lastSeenCursor)(msg)(updateCursor)
        case Left((_, err))                          => ZStream.fail(err)
      }
}

object Messenger {
  sealed trait MessengerTimeout
  object MessengerTimeout {
    case object NoTimeout                  extends MessengerTimeout
    case object InheritConfigTimeout       extends MessengerTimeout
    case class Timeout(duration: Duration) extends MessengerTimeout
  }
}
