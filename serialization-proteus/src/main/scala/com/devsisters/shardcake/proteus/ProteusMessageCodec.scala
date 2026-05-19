package com.devsisters.shardcake.proteus

import _root_.proteus.{ ProtobufCodec, ProtobufDeriver }
import com.devsisters.shardcake.{ Replier, StreamReplier }
import com.devsisters.shardcake.interfaces.MessageCodec

import scala.compiletime.{ erasedValue, summonFrom, summonInline }
import scala.deriving.Mirror

/**
 * An inline-derived `MessageCodec[Msg]` backed by Proteus.
 *
 * Walks `Msg` at compile time:
 *  - For the whole message it uses `ProtobufCodec.derived[Msg]` for encode/decode.
 *  - For each message shape containing a `Replier[R]` or `StreamReplier[R]` field, directly
 *    or inside nested case classes / sealed-trait variants, it derives `ProtobufCodec[R]`.
 *
 * Message shapes without a reply field do not participate in reply dispatch (they are
 * fire-and-forget messages). Product types (a single case class as `Msg`) are also supported.
 *
 * Recursive message graphs are not supported by the reply-selector derivation. Keep
 * recursive structures behind non-product containers or provide a custom codec if they need
 * reply discovery.
 */
object ProteusMessageCodec {

  private type VariantEntry = (Any => Array[Byte], Array[Byte] => Any)

  private enum ReplyEntrySummary {
    case None
    case One(entry: VariantEntry)
    case Many

    def combine(that: ReplyEntrySummary): ReplyEntrySummary =
      (this, that) match {
        case (ReplyEntrySummary.None, other)                          => other
        case (one @ ReplyEntrySummary.One(_), ReplyEntrySummary.None) => one
        case _                                                        => ReplyEntrySummary.Many
      }

    def soleEntry: Option[VariantEntry] =
      this match {
        case ReplyEntrySummary.One(entry)                    => Some(entry)
        case ReplyEntrySummary.None | ReplyEntrySummary.Many => scala.None
      }
  }

  private final case class ReplySelector[-A](select: A => Option[VariantEntry], summary: ReplyEntrySummary)

  /**
   * Derive a `MessageCodec[Msg]` using Proteus. Proteus requires `Msg` to be a message
   * (case class) or an enum / sealed trait at the root — primitives like `Int` / `String`
   * are not supported as message types directly; wrap them in a case class.
   *
   * For sealed traits and case classes, direct or nested `Replier[R]` / `StreamReplier[R]`
   * fields have their per-slot reply codec materialised at compile time.
   */
  inline def derived[Msg](using m: Mirror.Of[Msg], deriver: ProtobufDeriver): MessageCodec[Msg] =
    build(summonOrDeriveProtobufCodec[Msg](using deriver), deriveSelector[Msg](using m, deriver))

  private inline def summonOrDeriveProtobufCodec[A](using deriver: ProtobufDeriver): ProtobufCodec[A] =
    summonFrom {
      case codec: ProtobufCodec[A] => codec
      case _                       => ProtobufCodec.derived[A](using deriver)
    }

  private def build[Msg](
    msgCodec: ProtobufCodec[Msg],
    replySelector: ReplySelector[Msg]
  ): MessageCodec[Msg] = new Impl[Msg](msgCodec, replySelector)

  private final class Impl[Msg](
    msgCodec: ProtobufCodec[Msg],
    replySelector: ReplySelector[Msg]
  ) extends MessageCodec[Msg] {

    private val fallbackEncode: Any => Array[Byte] = _ => Array.emptyByteArray

    // With exactly one Replier-bearing variant there's only one possible reply type, so
    // the same encoder applies regardless of which variant the incoming request was.
    // Caching it also prevents `fallbackEncode` from ever being handed out for a request
    // that didn't carry the Replier (e.g. stream continuations).
    private val singleEncoder: Option[Any => Array[Byte]] =
      replySelector.summary.soleEntry.map(_._1)

    def encodeMessage(message: Msg): Array[Byte] = msgCodec.encode(message)
    def decodeMessage(bytes: Array[Byte]): Msg   = msgCodec.decode(bytes)

    def replyEncoder(decoded: Msg): Any => Array[Byte] =
      singleEncoder.getOrElse(replySelector.select(decoded).fold(fallbackEncode)(_._1))

    def replyDecoder[Res](sample: Msg): Array[Byte] => Res       = lookupDecoder(sample, "reply")
    def streamReplyDecoder[Res](sample: Msg): Array[Byte] => Res = lookupDecoder(sample, "stream reply")

    private def lookupDecoder[Res](sample: Msg, kind: String): Array[Byte] => Res =
      replySelector.select(sample) match {
        case Some((_, dec)) => bytes => dec(bytes).asInstanceOf[Res]
        case None           =>
          val message = s"No $kind codec registered for variant ${sample.getClass.getName}"
          (_: Array[Byte]) => sys.error(message)
      }
  }

  private inline def deriveSelector[A](using m: Mirror.Of[A], deriver: ProtobufDeriver): ReplySelector[A] =
    inline m match {
      case s: Mirror.SumOf[A]     =>
        val selectors = collectVariantSelectors[s.MirroredElemTypes]
        ReplySelector(
          select = value => selectors(s.ordinal(value)).select(value),
          summary = selectors.foldLeft(ReplyEntrySummary.None)(_ combine _.summary)
        )
      case p: Mirror.ProductOf[A] =>
        selectorForProduct[A, p.MirroredElemTypes]
    }

  private inline def collectVariantSelectors[Variants <: Tuple](using
    deriver: ProtobufDeriver
  ): Vector[ReplySelector[Any]] =
    inline erasedValue[Variants] match {
      case _: EmptyTuple     => Vector.empty
      case _: (head *: tail) =>
        val current = summonFrom {
          case m: Mirror.Of[`head`] => deriveSelector[head](using m, deriver).asInstanceOf[ReplySelector[Any]]
          case _                    => emptySelector[Any]
        }
        current +: collectVariantSelectors[tail]
    }

  private inline def selectorForProduct[A, Fields <: Tuple](using deriver: ProtobufDeriver): ReplySelector[A] =
    findDirectReplyType[Fields] match {
      case Some(entry) =>
        ReplySelector(_ => Some(entry), ReplyEntrySummary.One(entry))
      case None        =>
        val nested = nestedFieldSelectors[Fields](0)
        ReplySelector(
          select = value => {
            val product                        = value.asInstanceOf[Product]
            var remaining                      = nested
            var selected: Option[VariantEntry] = scala.None
            while (selected.isEmpty && remaining.nonEmpty) {
              val (index, selector) = remaining.head
              selected = selector.select(product.productElement(index))
              remaining = remaining.tail
            }
            selected
          },
          summary = nested.foldLeft(ReplyEntrySummary.None)(_ combine _._2.summary)
        )
    }

  private inline def findDirectReplyType[Fields <: Tuple](using deriver: ProtobufDeriver): Option[VariantEntry] =
    inline erasedValue[Fields] match {
      case _: EmptyTuple                 => None
      case _: (Replier[r] *: tail)       => Some(buildReplyEntry[r])
      case _: (StreamReplier[r] *: tail) => Some(buildReplyEntry[r])
      case _: (_ *: tail)                => findDirectReplyType[tail]
    }

  private inline def nestedFieldSelectors[Fields <: Tuple](index: Int)(using
    deriver: ProtobufDeriver
  ): List[(Int, ReplySelector[Any])] =
    inline erasedValue[Fields] match {
      case _: EmptyTuple                 => Nil
      case _: (Replier[?] *: tail)       => nestedFieldSelectors[tail](index + 1)
      case _: (StreamReplier[?] *: tail) => nestedFieldSelectors[tail](index + 1)
      case _: (head *: tail)             =>
        val rest = nestedFieldSelectors[tail](index + 1)
        summonFrom {
          case m: Mirror.Of[`head`] =>
            val selector = deriveSelector[head](using m, deriver).asInstanceOf[ReplySelector[Any]]
            selector.summary match {
              case ReplyEntrySummary.None                            => rest
              case ReplyEntrySummary.One(_) | ReplyEntrySummary.Many => (index -> selector) :: rest
            }
          case _                    =>
            rest
        }
    }

  private def emptySelector[A]: ReplySelector[A] =
    ReplySelector(_ => None, ReplyEntrySummary.None)

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
