package com.devsisters.shardcake

import com.coralogix.zio.k8s.client.model.K8sNamespace
import zio._

case class K8sConfig(cacheSize: Int, cacheDuration: Duration, namespace: Option[K8sNamespace])
