package com.devsisters.shardcake

import _root_.proteus.ProtobufDeriver
import com.devsisters.shardcake.interfaces.MessageCodec
import zio.blocks.schema.Schema

import scala.deriving.Mirror
import scala.util.NotGiven

/**
 * Proteus backend givens. Import everything in one shot with
 * `import com.devsisters.shardcake.proteus.given` to enable Proteus-backed serialisation
 * for all `EntityType` / `TopicType` declarations in the surrounding scope.
 *
 * Bring a custom `given ProtobufDeriver = ...` into scope (e.g. via Proteus's builder API
 * to register custom codec instances or modifiers) to override the default deriver — the
 * `defaultDeriver` exposed here only materialises when no other `ProtobufDeriver` is in
 * scope, so a user-provided one wins without an ambiguity error.
 *
 * `Schema[Unit]` is also exposed here as a convenience because Proteus cannot derive it
 * automatically (Unit has no fields). It is encoded as a single placeholder byte.
 */
package object proteus {
  given defaultDeriver(using NotGiven[ProtobufDeriver]): ProtobufDeriver = ProtobufDeriver

  inline given derive[Msg](using m: Mirror.Of[Msg], deriver: ProtobufDeriver): MessageCodec[Msg] =
    ProteusMessageCodec.derived[Msg](using m, deriver)

  given Schema[Unit] = Schema[Int].transform[Unit](_ => (), _ => 0)
}
