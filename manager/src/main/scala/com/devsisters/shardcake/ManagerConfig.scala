package com.devsisters.shardcake

import zio._

/**
 * Shard Manager configuration
 * @param numberOfShards number of shards (see documentation on how to choose this), should be same on all nodes
 * @param apiPort port to expose the GraphQL API
 * @param rebalanceInterval interval for regular rebalancing of shards
 * @param rebalanceRetryInterval retry interval for rebalancing when some shards failed to be rebalanced
 * @param pingTimeout time to wait for a pod to respond to a ping request
 * @param persistRetryInterval retry interval for persistence of pods and shard assignments
 * @param persistRetryCount max retry count for persistence of pods and shard assignments
 * @param rebalanceRate max ratio of shards to rebalance at once
 * @param podHealthCheckInterval interval for checking pod health
 */
case class ManagerConfig(
  numberOfShards: Int,
  apiPort: Int,
  rebalanceInterval: Duration,
  rebalanceRetryInterval: Duration,
  pingTimeout: Duration,
  persistRetryInterval: Duration,
  persistRetryCount: Int,
  rebalanceRate: Double,
  podHealthCheckInterval: Duration
)

object ManagerConfig {
  val default: ManagerConfig =
    ManagerConfig(
      numberOfShards = 300,
      apiPort = 8080,
      rebalanceInterval = 20 seconds,
      rebalanceRetryInterval = 10 seconds,
      pingTimeout = 3 seconds,
      persistRetryInterval = 3 seconds,
      persistRetryCount = 100,
      rebalanceRate = 2 / 100d,
      podHealthCheckInterval = 1 minute
    )
}
