package com.devsisters.sharding.errors

case class EntityNotManagedByThisPod(entityId: String)
    extends Exception(s"Entity $entityId is not managed by this pod.")
