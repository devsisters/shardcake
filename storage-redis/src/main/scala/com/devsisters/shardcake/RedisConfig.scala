package com.devsisters.shardcake

/**
 * The configuration for the Redis storage implementation.
 * @param assignmentsKey a function from a role to the key to use to store shard assignments
 * @param podsKey the key to use to store registered pods
 */
case class RedisConfig(assignmentsKey: Role => String, podsKey: String)

object RedisConfig {
  val default: RedisConfig =
    RedisConfig(assignmentsKey = role => s"shard_assignments:${role.name}", podsKey = "pods")
}
