package com.devsisters.sharding

import com.coralogix.zio.k8s.client.model.FieldSelector
import com.coralogix.zio.k8s.client.v1.pods.Pods
import com.devsisters.sharding.interfaces.PodsHealth
import zio._
import zio.cache.{ Cache, Lookup }

object K8sPodsHealth {
  val live: URLayer[Pods with K8sConfig, PodsHealth] =
    ZLayer {
      for {
        pods   <- ZIO.service[Pods.Service]
        config <- ZIO.service[K8sConfig]
        cache  <- Cache
                    .make(
                      config.cacheSize,
                      config.cacheDuration,
                      Lookup { (podAddress: PodAddress) =>
                        pods
                          .getAll(
                            config.namespace,
                            1,
                            Some(FieldSelector.FieldEquals(Chunk("status", "podIP"), podAddress.host))
                          )
                          .runHead
                          .map(_.isDefined)
                          .tap(ZIO.unless(_)(ZIO.logWarning(s"$podAddress is not found in k8s")))
                          .catchAllCause(cause => ZIO.logErrorCause(s"Error communicating with k8s", cause).as(true))
                      }
                    )
      } yield new PodsHealth {
        def isAlive(podAddress: PodAddress): UIO[Boolean] = cache.get(podAddress)
      }
    }
}
