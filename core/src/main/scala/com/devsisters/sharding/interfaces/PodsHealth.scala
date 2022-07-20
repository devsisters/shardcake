package com.devsisters.sharding.interfaces

import com.devsisters.sharding.PodAddress
import zio.{ UIO, ULayer, ZIO, ZLayer }

trait PodsHealth {
  def isAlive(podAddress: PodAddress): UIO[Boolean]
}

object PodsHealth {
  val noop: ULayer[PodsHealth] =
    ZLayer.succeed(new PodsHealth {
      def isAlive(podAddress: PodAddress): UIO[Boolean] = ZIO.succeed(true)
    })

  val local: ZLayer[Pods, Nothing, PodsHealth] =
    ZLayer {
      ZIO.serviceWith[Pods](podApi => (podAddress: PodAddress) => podApi.ping(podAddress).option.map(_.isDefined))
    }
}
