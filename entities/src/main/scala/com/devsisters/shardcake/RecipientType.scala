package com.devsisters.shardcake

/**
 * An abstract type to extend for each type of entity or topic
 * @param name a unique string that identifies this entity type or topic
 * @tparam Msg the type of message that can be sent to this entity type or topic
 */
sealed abstract class RecipientType[+Msg](val name: String)
abstract class EntityType[+Msg](name: String) extends RecipientType[Msg](name)
abstract class Topic[+Msg](name: String)  extends RecipientType[Msg](name)
