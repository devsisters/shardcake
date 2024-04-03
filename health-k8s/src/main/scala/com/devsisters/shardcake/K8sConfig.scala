package com.devsisters.shardcake

import com.coralogix.zio.k8s.client.model.{ K8sNamespace, LabelSelector }
import zio._

/**
 * Configuration for K8sPodsHealth
 * @param cacheSize how many items we keep in the pod status cache
 * @param cacheDuration how long we keep items in the pod status cache
 * @param namespace namespace to query pods from (if None, query all namespaces)
 * @param labelSelector allows to filter pods by k8s labels
 */
case class K8sConfig(
  cacheSize: Int,
  cacheDuration: Duration,
  namespace: Option[K8sNamespace],
  labelSelector: Option[LabelSelector]
)

object K8sConfig {
  val default: K8sConfig = K8sConfig(500, 3 seconds, None, None)
}
