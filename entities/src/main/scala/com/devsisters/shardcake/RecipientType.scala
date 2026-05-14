package com.devsisters.shardcake

import com.devsisters.shardcake.interfaces.MessageCodec

/**
 * An abstract type to extend for each type of entity or topic.
 *
 * The `MessageCodec[Msg]` carried via the implicit parameter owns serialisation for this
 * recipient type. Users provide a codec by importing one of the backend `given`s in scope
 * at the declaration site, e.g. `import com.devsisters.shardcake.kryo.given`.
 *
 * @param name a unique string that identifies this entity or topic type
 * @tparam Msg the type of message that can be sent to this entity or topic type
 */
sealed abstract class RecipientType[Msg](val name: String)(using private[shardcake] val codec: MessageCodec[Msg]) {
  def getShardId(entityId: String, numberOfShards: Int): ShardId =
    math.abs(entityId.hashCode % numberOfShards) + 1
}
abstract class EntityType[Msg](name: String)(using MessageCodec[Msg]) extends RecipientType[Msg](name)
abstract class TopicType[Msg](name: String)(using MessageCodec[Msg]) extends RecipientType[Msg](name)
