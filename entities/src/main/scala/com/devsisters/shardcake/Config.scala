package com.devsisters.shardcake

import sttp.client3.UriContext
import sttp.model.Uri
import zio.duration._

/**
 * Sharding configuration
 * @param numberOfShards number of shards (see documentation on how to choose this), should be same on all nodes
 * @param selfHost hostname or IP address of the current pod
 * @param shardingPort port used for pods to communicate together
 * @param shardManagerUri url of the Shard Manager GraphQL API
 * @param serverVersion version of the current pod
 * @param entityMaxIdleTime time of inactivity (without receiving any message) after which an entity will be interrupted
 * @param entityTerminationTimeout time we give to an entity to handle the termination message before interrupting it
 * @param sendTimeout timeout when calling sendMessage
 * @param refreshAssignmentsRetryInterval retry interval in case of failure getting shard assignments from storage
 * @param unhealthyPodReportInterval interval to report unhealthy pods to the Shard Manager (this exists to prevent calling the Shard Manager for each failed message)
 * @param simulateRemotePods disable optimizations when sending a message to an entity hosted on the local shards (this will force serialization of all messages)
 */
case class Config(
  numberOfShards: Int,
  selfHost: String,
  shardingPort: Int,
  shardManagerUri: Uri,
  serverVersion: String,
  entityMaxIdleTime: Duration,
  entityTerminationTimeout: Duration,
  sendTimeout: Duration,
  refreshAssignmentsRetryInterval: Duration,
  unhealthyPodReportInterval: Duration,
  simulateRemotePods: Boolean
)

object Config {
  val default: Config = Config(
    numberOfShards = 300,
    selfHost = "localhost",
    shardingPort = 54321,
    shardManagerUri = uri"http://localhost:8080/api/graphql",
    serverVersion = "1.0.0",
    entityMaxIdleTime = 1 minute,
    entityTerminationTimeout = 3 seconds,
    sendTimeout = 10 seconds,
    refreshAssignmentsRetryInterval = 5 seconds,
    unhealthyPodReportInterval = 5 seconds,
    simulateRemotePods = false
  )
}
