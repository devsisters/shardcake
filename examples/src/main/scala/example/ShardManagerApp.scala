package example

import com.devsisters.shardcake._
import com.devsisters.shardcake.interfaces.PodsHealth
import zio._

object ShardManagerApp extends ZIOAppDefault {

  private val managerConfig = ZLayer.succeed(ManagerConfig(300, 8080))
  private val grpcConfig    = ZLayer.succeed(GrpcConfig(32 * 1024 * 1024))

  def run: Task[Nothing] =
    Server.run.provide(
      managerConfig,
      grpcConfig,
      redis,
      PodsHealth.local,
      GrpcPods.live,
      StorageRedis.live,
      ShardManager.live
    )

}
