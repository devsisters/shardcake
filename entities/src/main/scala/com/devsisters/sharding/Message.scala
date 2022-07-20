package com.devsisters.sharding

case class Message(entityType: EntityType, body: Any, entityId: String)
