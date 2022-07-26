package com.devsisters.shardcake

/**
 * An abstract type to extend for each type of entity or topic
 * @param name a unique string that identifies this entity or topic type
 * @tparam Msg the type of message that can be sent to this entity or topic type
 */
sealed abstract class RecipientType[+Msg](val name: String) {
  def getShardId(entityId: String, numberOfShards: Int): ShardId =
    math.abs(entityId.hashCode % numberOfShards) + 1
}
abstract class EntityType[+Msg](name: String) extends RecipientType[Msg](name)
abstract class TopicType[+Msg](name: String) extends RecipientType[Msg](name)
