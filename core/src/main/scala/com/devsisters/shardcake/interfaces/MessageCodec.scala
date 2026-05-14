package com.devsisters.shardcake.interfaces

import com.devsisters.shardcake.interfaces.MessageCodec.{ Decoder, Encoder }

/**
 * Per-message-type codec used by Shardcake to serialise entity / topic messages and
 * their replies on the wire.
 *
 * Carried by `RecipientType[Msg]` via an implicit `MessageCodec[Msg]` parameter, so each
 * `EntityType` / `TopicType` declaration picks up the codec from the surrounding scope.
 *
 * Implementations decide how `Msg` is encoded and — for reply-bearing variants — which
 * encoder/decoder pair to use for the reply type.
 * For uniform backends (e.g. Kryo) the reply codec is the same regardless of which
 * variant the message is; for variant-aware backends (e.g. Proteus) it depends on the
 * concrete variant.
 */
trait MessageCodec[Msg] {

  /**
   * Encode a message to bytes for transport.
   */
  def encodeMessage(message: Msg): Array[Byte]

  /**
   * Decode a message from bytes.
   */
  def decodeMessage(bytes: Array[Byte]): Msg

  /**
   * Gets an encoder for the reply of a specific message on the receiver side.
   * The returned function will be invoked when the entity calls `replier.reply(value)`
   * or `streamReplier.replyStream(...)`.
   */
  def replyEncoder(decoded: Msg): Encoder

  /**
   * Returns a decoder for the reply of a specific message.
   */
  def replyDecoder[Res](sample: Msg): Decoder[Res]

  /**
   * Same as `replyDecoder`, but for stream-reply slots.
   */
  def streamReplyDecoder[Res](sample: Msg): Decoder[Res]
}

object MessageCodec {
  type Encoder    = Any => Array[Byte]
  type Decoder[A] = Array[Byte] => A
}
