package com.devsisters.shardcake

/**
 * An abstract type to extend for each type of entity
 * @param name a unique string that identifies this entity type
 * @tparam Msg the type of message that can be sent to this entity type
 */
abstract class EntityType[+Msg](val name: String)
