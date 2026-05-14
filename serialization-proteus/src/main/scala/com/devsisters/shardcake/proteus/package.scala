package com.devsisters.shardcake

import _root_.proteus.ProtobufDeriver
import com.devsisters.shardcake.interfaces.MessageCodec
import zio.blocks.schema.Schema

import scala.deriving.Mirror

/**
 * Proteus backend givens. Import everything in one shot with
 * `import com.devsisters.shardcake.proteus.given` to enable Proteus-backed serialisation
 * for all `EntityType` / `TopicType` declarations in the surrounding scope.
 *
 * Bring a custom `given ProtobufDeriver = ...` into scope (e.g. via Proteus's builder API
 * to register custom codec instances or modifiers) to override the default deriver.
 *
 * `Schema[Unit]` is also exposed here as a convenience because Proteus cannot derive it
 * automatically (Unit has no fields). It is encoded as a single placeholder byte.
 */
package object proteus {
  given defaultDeriver: ProtobufDeriver = ProtobufDeriver

  inline given derive[Msg](using m: Mirror.Of[Msg], deriver: ProtobufDeriver): MessageCodec[Msg] =
    ProteusMessageCodec.derived[Msg]

  given Schema[Unit] = Schema[Byte].transform[Unit](_ => (), _ => 0)
}
