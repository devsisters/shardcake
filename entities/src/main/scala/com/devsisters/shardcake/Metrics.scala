package com.devsisters.shardcake

import zio.metrics.Metric
import zio.metrics.Metric.Gauge

object Metrics {
  val shards: Gauge[Double]     = Metric.gauge("shardcake.shards")
  val entities: Gauge[Double]   = Metric.gauge("shardcake.entities")
  val singletons: Gauge[Double] = Metric.gauge("shardcake.singletons")
}
