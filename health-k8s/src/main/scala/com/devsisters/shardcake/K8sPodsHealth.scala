package com.devsisters.shardcake

import com.coralogix.zio.k8s.client.model.FieldSelector
import com.coralogix.zio.k8s.client.v1.pods.Pods
import com.devsisters.shardcake.interfaces.{ Logging, PodsHealth }
import zio._
import zio.cache.{ Cache, Lookup }

object K8sPodsHealth {

  /**
   * A layer for PodsHealth that checks if the node exists in Kubernetes.
   */
  val live: URLayer[Pods with Has[K8sConfig] with Has[Logging], Has[PodsHealth]] =
    (for {
      pods   <- ZIO.service[Pods.Service]
      config <- ZIO.service[K8sConfig]
      logger <- ZIO.service[Logging]
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
                        .tap(ZIO.unless(_)(logger.logWarning(s"$podAddress is not found in k8s")))
                        .catchAllCause(cause => logger.logErrorCause(s"Error communicating with k8s", cause).as(true))
                    }
                  )
    } yield new PodsHealth {
      def isAlive(podAddress: PodAddress): UIO[Boolean] = cache.get(podAddress)
    }).toLayer
}
