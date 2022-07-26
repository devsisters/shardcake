package example

import com.devsisters.shardcake._
import com.devsisters.shardcake.interfaces.PodsHealth
import zio._

object ShardManagerApp extends ZIOAppDefault {

  private val managerConfig = ZLayer.succeed(ManagerConfig.default)
  private val grpcConfig    = ZLayer.succeed(GrpcConfig.default)
  private val redisConfig   = ZLayer.succeed(RedisConfig.default)

  def run: Task[Nothing] =
    Server.run.provide(
      managerConfig,
      grpcConfig,
      redisConfig,
      redis,
      PodsHealth.local,
      GrpcPods.live,
      StorageRedis.live,
      ShardManager.live
    )
}
