package com.devsisters.shardcake

/**
 * An abstract type to extend for each type of entity
 * @param name a unique string that identifies this entity type
 * @tparam Msg the type of message that can be sent to this entity type
 */
sealed abstract class RecipientType[+Msg](val name: String)
abstract class EntityType[+Msg](name: String) extends RecipientType[Msg](name)
abstract class TopicType[+Msg](name: String)  extends RecipientType[Msg](name)
