package com.devsisters.shardcake.errors

/**
 * Exception indicating that a shard id is invalid.
 */
case class InvalidShardId(shardId: Int) extends Exception(s"Invalid shard id: $shardId")
