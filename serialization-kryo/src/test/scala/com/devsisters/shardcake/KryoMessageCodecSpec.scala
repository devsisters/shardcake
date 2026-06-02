package com.devsisters.shardcake

import com.devsisters.shardcake.interfaces.MessageCodec
import com.devsisters.shardcake.kryo.given
import zio.Scope
import zio.test._

object KryoMessageCodecSpec extends ZIOSpecDefault {

  sealed trait Msg
  object Msg {
    final case class Plain(name: String, count: Int)                extends Msg
    final case class WithReplier(id: Int, replier: Replier[String]) extends Msg
    final case class WithStreamReplier(replier: StreamReplier[Int]) extends Msg
  }

  private val codec = summon[MessageCodec[Msg]]

  def spec: Spec[TestEnvironment with Scope, Any] =
    suite("KryoMessageCodecSpec")(
      test("roundtrips a message with no Replier") {
        val msg     = Msg.Plain("hello", 42)
        val bytes   = codec.encodeMessage(msg)
        val decoded = codec.decodeMessage(bytes)
        assertTrue(decoded == msg)
      },
      test("roundtrips a message containing a Replier and produces a working receiver encoder") {
        val msg        = Msg.WithReplier(7, Replier("rid-1"))
        val bytes      = codec.encodeMessage(msg)
        val decoded    = codec.decodeMessage(bytes).asInstanceOf[Msg.WithReplier]
        val encoder    = codec.replyEncoder(decoded)
        val replyBytes = encoder("ok")
        val decodedStr = codec.replyDecoder[String](msg)(replyBytes)
        assertTrue(decoded.id == 7, decoded.replier.id == "rid-1", decodedStr == "ok")
      },
      test("roundtrips a message containing a StreamReplier and produces a working receiver encoder") {
        val msg        = Msg.WithStreamReplier(StreamReplier("srid-1"))
        val bytes      = codec.encodeMessage(msg)
        val decoded    = codec.decodeMessage(bytes).asInstanceOf[Msg.WithStreamReplier]
        val encoder    = codec.replyEncoder(decoded)
        val replyBytes = encoder(99)
        val decodedInt = codec.streamReplyDecoder[Int](msg)(replyBytes)
        assertTrue(decoded.replier.id == "srid-1", decodedInt == 99)
      }
    )
}
