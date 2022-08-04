package example.complex

import com.devsisters.shardcake._
import com.devsisters.shardcake.interfaces._
import zio._

object ShardManagerApp extends ZIOAppDefault {
  def run: Task[Nothing] =
    Server.run.provide(
      ZLayer.succeed(ManagerConfig.default),
      ZLayer.succeed(GrpcConfig.default),
      ZLayer.succeed(RedisConfig.default),
      redis,
      StorageRedis.live, // store data in Redis
      PodsHealth.local,  // just ping a pod to see if it's alive
      GrpcPods.live,     // use gRPC protocol
      ShardManager.live  // Shard Manager logic
    )
}
