package com.devsisters.shardcake.interfaces

import com.devsisters.shardcake.PodAddress
import zio.{ UIO, ULayer, ZIO, ZLayer }

/**
 * An interface to check a pod's health.
 * This is used when a pod is unresponsive, to check if it should be unassigned all its shards or not.
 * If the pod is alive, shards will not be unassigned because the pods might still be processing messages and might be responsive again.
 * If the pod is not alive, shards can be safely reassigned somewhere else.
 * A typical implementation for this is using k8s to check if the pod still exists.
 */
trait PodsHealth {

  /**
   * Check if a pod is still alive.
   */
  def isAlive(podAddress: PodAddress): UIO[Boolean]
}

object PodsHealth {

  /**
   * A layer that considers pods as always alive.
   * This is useful for testing only.
   */
  val noop: ULayer[PodsHealth] =
    ZLayer.succeed(new PodsHealth {
      def isAlive(podAddress: PodAddress): UIO[Boolean] = ZIO.succeed(true)
    })

  /**
   * A layer that pings the pod directly to check if it's alive.
   * This is useful for developing and testing but not reliable in production.
   */
  val local: ZLayer[Pods, Nothing, PodsHealth] =
    ZLayer {
      ZIO.serviceWith[Pods](podApi => (podAddress: PodAddress) => podApi.ping(podAddress).option.map(_.isDefined))
    }
}
