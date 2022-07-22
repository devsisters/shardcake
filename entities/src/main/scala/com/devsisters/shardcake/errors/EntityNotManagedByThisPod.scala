package com.devsisters.shardcake.errors

/**
 * Exception indicating that a message was sent to a pod that is not in charge of the given entity.
 * This is expected during rebalancing and will be retried.
 */
case class EntityNotManagedByThisPod(entityId: String)
    extends Exception(s"Entity $entityId is not managed by this pod.")
