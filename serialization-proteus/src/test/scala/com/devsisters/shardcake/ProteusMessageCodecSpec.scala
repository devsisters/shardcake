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

  sealed trait NestedMsg derives _root_.proteus.ProtobufCodec
  object NestedMsg {
    final case class ToProtocol(request: Protocol[Command, PEvent]) extends NestedMsg
    final case class Plain(payload: String)                         extends NestedMsg
  }

  sealed trait Protocol[Command, PEvent]
  object Protocol {
    final case class Run[Command, PEvent](command: Command)                            extends Protocol[Command, PEvent]
    final case class Prepare[Command, PEvent](transactionId: String, command: Command) extends Protocol[Command, PEvent]
    final case class ReadyToPersist[Command, PEvent](
      transactionId: String,
      replier: Replier[PersistReadied[PEvent]]
    ) extends Protocol[Command, PEvent]
    final case class Commit[Command, PEvent](transactionId: String)                    extends Protocol[Command, PEvent]

    given _root_.proteus.ProtobufCodec[Protocol[ProteusMessageCodecSpec.Command, ProteusMessageCodecSpec.PEvent]] =
      _root_.proteus.ProtobufCodec.derived
  }

  final case class Command(context: String, function: Function, replier: Replier[CommandReply])
      derives _root_.proteus.ProtobufCodec

  sealed trait Function derives _root_.proteus.ProtobufCodec
  object Function {
    final case class Add(amount: Int) extends Function
    final case class Get()            extends Function
  }

  sealed trait CommandReply derives _root_.proteus.ProtobufCodec
  object CommandReply {
    final case class Added(total: Int) extends CommandReply
    final case class Got(total: Int)   extends CommandReply
  }

  sealed trait PEvent derives _root_.proteus.ProtobufCodec
  object PEvent {
    final case class Changed(delta: Int) extends PEvent
  }

  final case class PersistReadied[PEvent](persistenceId: String, events: List[PEvent], fromSequenceNr: Long)

  object PersistReadied {
    given _root_.proteus.ProtobufCodec[PersistReadied[ProteusMessageCodecSpec.PEvent]] =
      _root_.proteus.ProtobufCodec.derived
  }

  private val codec       = summon[MessageCodec[Msg]]
  private val nestedCodec = summon[MessageCodec[NestedMsg]]

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
      },
      test("finds a Replier nested inside products and sealed-trait variants") {
        val msg        = NestedMsg.ToProtocol(
          Protocol.Run(Command("ctx", Function.Add(1), Replier("nested-rid")))
        )
        val bytes      = nestedCodec.encodeMessage(msg)
        val decoded    = nestedCodec.decodeMessage(bytes).asInstanceOf[NestedMsg.ToProtocol]
        val encoder    = nestedCodec.replyEncoder(decoded)
        val reply      = CommandReply.Added(2)
        val replyBytes = encoder(reply)
        val decoded2   = nestedCodec.replyDecoder[CommandReply](msg)(replyBytes)

        assertTrue(
          decoded.request.asInstanceOf[Protocol.Run[Command, PEvent]].command.replier.id == "nested-rid",
          decoded2 == reply
        )
      },
      test("finds concrete generic reply types in nested protocol variants") {
        val msg        = NestedMsg.ToProtocol(
          Protocol.ReadyToPersist[Command, PEvent]("tid", Replier("ready-rid"))
        )
        val bytes      = nestedCodec.encodeMessage(msg)
        val decoded    = nestedCodec.decodeMessage(bytes).asInstanceOf[NestedMsg.ToProtocol]
        val encoder    = nestedCodec.replyEncoder(decoded)
        val reply      = PersistReadied("pid", List(PEvent.Changed(3)), 4L)
        val replyBytes = encoder(reply)
        val decoded2   = nestedCodec.replyDecoder[PersistReadied[PEvent]](msg)(replyBytes)

        assertTrue(
          decoded.request.asInstanceOf[Protocol.ReadyToPersist[Command, PEvent]].replier.id == "ready-rid",
          decoded2 == reply
        )
      }
    )
}
