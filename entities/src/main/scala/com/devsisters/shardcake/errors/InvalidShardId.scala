package com.devsisters.shardcake.errors

/**
 * Exception indicating that a shard id is invalid.
 */
case class InvalidShardId(entityId: String, shardId: Int)
    extends Exception(s"Invalid shard id: $shardId for entity $entityId")
