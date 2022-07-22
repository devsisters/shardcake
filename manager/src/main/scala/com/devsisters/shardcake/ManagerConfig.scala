package com.devsisters.shardcake

/**
 * Shard Manager configuration
 * @param numberOfShards number of shards (see documentation on how to choose this), should be same on all nodes
 * @param apiPort port to expose the GraphQL API
 */
case class ManagerConfig(numberOfShards: Int, apiPort: Int)
