package com.devsisters.shardcake

import zio.metrics.Metric
import zio.metrics.Metric.{ Counter, Gauge }

object ManagerMetrics {
  val pods: Gauge[Double]             = Metric.gauge("shardcake.pods")
  val assignedShards: Gauge[Double]   = Metric.gauge("shardcake.shards_assigned")
  val unassignedShards: Gauge[Double] = Metric.gauge("shardcake.shards_unassigned")

  val rebalances: Counter[Long]       = Metric.counter("shardcake.rebalances")
  val podHealthChecked: Counter[Long] = Metric.counter("shardcake.pod_health_checked")
}
