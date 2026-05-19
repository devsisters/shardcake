package com.devsisters.shardcake.proteus

import _root_.proteus.{ ProtobufCodec, ProtobufDeriver }
import com.devsisters.shardcake.{ Replier, StreamReplier }
import com.devsisters.shardcake.interfaces.MessageCodec

import scala.compiletime.{ erasedValue, summonFrom, summonInline }
import scala.deriving.Mirror
import scala.reflect.ClassTag

/**
 * A macro-derived `MessageCodec[Msg]` backed by Proteus.
 *
 * Walks `Msg` at compile time:
 *  - For the whole message it uses `ProtobufCodec.derived[Msg]` for encode/decode.
 *  - For each sealed-trait variant containing a `Replier[R]` or `StreamReplier[R]` field
 *    it derives `ProtobufCodec[R]` and registers a per-variant encoder/decoder. At
 *    runtime, `replyEncoder` and `replyDecoder` dispatch via the sample message's class.
 *
 * Variants without a reply field do not participate in the dispatch table (they are
 * fire-and-forget messages). Product types (a single case class as `Msg`) are also
 * supported.
 */
object ProteusMessageCodec {

  private type VariantEntry = (Any => Array[Byte], Array[Byte] => Any)

  /**
   * Derive a `MessageCodec[Msg]` using Proteus. Proteus requires `Msg` to be a message
   * (case class) or an enum / sealed trait at the root — primitives like `Int` / `String`
   * are not supported as message types directly; wrap them in a case class.
   *
   * For sealed traits, variants containing a `Replier[R]` / `StreamReplier[R]` field have
   * their per-slot reply codec materialised at compile time.
   */
  inline def derived[Msg](using m: Mirror.Of[Msg], deriver: ProtobufDeriver): MessageCodec[Msg] =
    build(summonOrDeriveProtobufCodec[Msg](using deriver), collectVariantEntries[Msg](using m, deriver).toMap)

  private inline def summonOrDeriveProtobufCodec[A](using deriver: ProtobufDeriver): ProtobufCodec[A] =
    summonFrom {
      case codec: ProtobufCodec[A] => codec
      case _                       => ProtobufCodec.derived[A](using deriver)
    }

  private def build[Msg](
    msgCodec: ProtobufCodec[Msg],
    classToEntry: Map[Class[?], VariantEntry]
  ): MessageCodec[Msg] = new Impl[Msg](msgCodec, classToEntry)

  private final class Impl[Msg](
    msgCodec: ProtobufCodec[Msg],
    classToEntry: Map[Class[?], VariantEntry]
  ) extends MessageCodec[Msg] {

    private val fallbackEncode: Any => Array[Byte] = _ => Array.emptyByteArray

    def encodeMessage(message: Msg): Array[Byte] = msgCodec.encode(message)
    def decodeMessage(bytes: Array[Byte]): Msg   = msgCodec.decode(bytes)

    def replyEncoder(decoded: Msg): Any => Array[Byte] =
      classToEntry.get(decoded.getClass).fold(fallbackEncode)(_._1)

    def replyDecoder[Res](sample: Msg): Array[Byte] => Res       = lookupDecoder(sample, "reply")
    def streamReplyDecoder[Res](sample: Msg): Array[Byte] => Res = lookupDecoder(sample, "stream reply")

    private def lookupDecoder[Res](sample: Msg, kind: String): Array[Byte] => Res =
      classToEntry.get(sample.getClass) match {
        case Some((_, dec)) => bytes => dec(bytes).asInstanceOf[Res]
        case None           =>
          val message = s"No $kind codec registered for variant ${sample.getClass.getName}"
          (_: Array[Byte]) => sys.error(message)
      }
  }

  private inline def collectVariantEntries[Msg](using
    m: Mirror.Of[Msg],
    deriver: ProtobufDeriver
  ): List[(Class[?], VariantEntry)] =
    inline m match {
      case s: Mirror.SumOf[Msg]     => collectFromVariants[s.MirroredElemTypes]
      case p: Mirror.ProductOf[Msg] =>
        val ct = summonInline[ClassTag[Msg]]
        entryForProduct[Msg, p.MirroredElemTypes].map(ct.runtimeClass -> _).toList
    }

  private inline def collectFromVariants[Variants <: Tuple](using
    deriver: ProtobufDeriver
  ): List[(Class[?], VariantEntry)] =
    inline erasedValue[Variants] match {
      case _: EmptyTuple     => Nil
      case _: (head *: tail) =>
        val rest      = collectFromVariants[tail]
        val variantCt = summonInline[ClassTag[head]]
        summonFrom {
          case p: Mirror.ProductOf[`head`] =>
            entryForProduct[head, p.MirroredElemTypes] match {
              case Some(entry) => (variantCt.runtimeClass, entry) :: rest
              case None        => rest
            }
          case _                           => rest
        }
    }

  private inline def entryForProduct[V, Fields <: Tuple](using deriver: ProtobufDeriver): Option[VariantEntry] =
    findReplyType[Fields]

  private inline def findReplyType[Fields <: Tuple](using deriver: ProtobufDeriver): Option[VariantEntry] =
    inline erasedValue[Fields] match {
      case _: EmptyTuple                 => None
      case _: (Replier[r] *: tail)       => Some(buildReplyEntry[r])
      case _: (StreamReplier[r] *: tail) => Some(buildReplyEntry[r])
      case _: (_ *: tail)                => findReplyType[tail]
    }

  /**
   * Materialise the per-reply-type encoder/decoder pair for one Replier/StreamReplier slot.
   * Prefers an in-scope `ProtobufCodec[R]`; otherwise derives one from a Mirror. Emits a
   * clear compile error when neither is available — Proteus needs a case class / sealed
   * trait / enum at the root and can't encode bare primitives.
   */
  private inline def buildReplyEntry[R](using deriver: ProtobufDeriver): VariantEntry =
    summonFrom {
      case codec: ProtobufCodec[R] =>
        (
          (v: Any) => codec.encode(v.asInstanceOf[R]),
          (b: Array[Byte]) => codec.decode(b)
        )
      case _: Mirror.Of[R]         =>
        val codec = ProtobufCodec.derived[R](using deriver)
        (
          (v: Any) => codec.encode(v.asInstanceOf[R]),
          (b: Array[Byte]) => codec.decode(b)
        )
      case _                       =>
        scala.compiletime.error(
          "Cannot derive a Proteus MessageCodec: reply type has no Mirror or in-scope ProtobufCodec. " +
            "Proteus requires a case class, sealed trait, or enum at the root — " +
            "wrap primitive reply types (Int, String, etc.) in a case class."
        )
    }
}
