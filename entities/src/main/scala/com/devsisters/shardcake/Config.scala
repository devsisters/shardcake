package com.devsisters.shardcake

import sttp.model.Uri

/**
 * Sharding configuration
 * @param numberOfShards number of shards (see documentation on how to choose this), should be same on all nodes
 * @param selfHost hostname or IP address of the current pod
 * @param shardingPort port used for pods to communicate together
 * @param shardManagerUri url of the Shard Manager GraphQL API
 * @param serverVersion version of the current pod
 */
case class Config(
  numberOfShards: Int,
  selfHost: String,
  shardingPort: Int,
  shardManagerUri: Uri,
  serverVersion: String
)
