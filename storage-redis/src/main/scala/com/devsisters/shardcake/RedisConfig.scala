package com.devsisters.shardcake

/**
 * The configuration for the Redis storage implementation.
 * @param assignmentsKey the key to store shard assignments
 * @param podsKey the key to store registered pods
 */
case class RedisConfig(assignmentsKey: String, podsKey: String)

object RedisConfig {
  val default: RedisConfig = RedisConfig(assignmentsKey = "shard_assignments", podsKey = "pods")
}
