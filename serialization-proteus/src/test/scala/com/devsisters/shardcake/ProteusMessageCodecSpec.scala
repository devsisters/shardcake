package com.devsisters.shardcake

import com.devsisters.shardcake.interfaces.MessageCodec
import com.devsisters.shardcake.proteus.given
import zio.Scope
import zio.test._

object ProteusMessageCodecSpec extends ZIOSpecDefault {

  sealed trait Msg derives _root_.proteus.ProtobufCodec
  object Msg {
    final case class Plain(name: String, count: Int)                        extends Msg
    final case class WithReplier(id: Int, replier: Replier[Reply])          extends Msg
    final case class WithStreamReplier(replier: StreamReplier[StreamReply]) extends Msg
    final case class FireAndForget(payload: String)                         extends Msg
  }

  final case class Reply(text: String, ok: Boolean) derives _root_.proteus.ProtobufCodec
  final case class StreamReply(value: Int) derives _root_.proteus.ProtobufCodec

  private val codec = summon[MessageCodec[Msg]]

  def spec: Spec[TestEnvironment with Scope, Any] =
    suite("ProteusMessageCodecSpec")(
      test("roundtrips a message with no Replier") {
        val msg     = Msg.Plain("hello", 42)
        val bytes   = codec.encodeMessage(msg)
        val decoded = codec.decodeMessage(bytes)
        assertTrue(decoded == msg)
      },
      test("roundtrips a fire-and-forget variant") {
        val msg     = Msg.FireAndForget("x")
        val bytes   = codec.encodeMessage(msg)
        val decoded = codec.decodeMessage(bytes)
        assertTrue(decoded == msg)
      },
      test("dispatches replyEncoder by variant and round-trips the reply") {
        val msg        = Msg.WithReplier(7, Replier("rid-1"))
        val bytes      = codec.encodeMessage(msg)
        val decoded    = codec.decodeMessage(bytes).asInstanceOf[Msg.WithReplier]
        val encoder    = codec.replyEncoder(decoded)
        val reply      = Reply("ok", true)
        val replyBytes = encoder(reply)
        val decoded2   = codec.replyDecoder[Reply](msg)(replyBytes)
        assertTrue(decoded.id == 7, decoded.replier.id == "rid-1", decoded2 == reply)
      },
      test("dispatches stream reply encoder by variant and round-trips the reply") {
        val msg        = Msg.WithStreamReplier(StreamReplier("srid-1"))
        val bytes      = codec.encodeMessage(msg)
        val decoded    = codec.decodeMessage(bytes).asInstanceOf[Msg.WithStreamReplier]
        val encoder    = codec.replyEncoder(decoded)
        val reply      = StreamReply(99)
        val replyBytes = encoder(reply)
        val decoded2   = codec.streamReplyDecoder[StreamReply](msg)(replyBytes)
        assertTrue(decoded.replier.id == "srid-1", decoded2 == reply)
      }
    )
}
