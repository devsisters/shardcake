package com.devsisters.shardcake.interfaces

import com.devsisters.shardcake.PodAddress
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
