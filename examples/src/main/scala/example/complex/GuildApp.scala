package example.complex

import com.devsisters.shardcake._
import com.devsisters.shardcake.interfaces.Serialization
import dev.profunktor.redis4cats.RedisCommands
import example.complex.GuildBehavior.GuildMessage.Join
import example.complex.GuildBehavior._
import zio._

object GuildApp extends ZIOAppDefault {
  val config: ZLayer[Any, SecurityException, Config] =
    ZLayer(
      System
        .env("port")
        .map(_.flatMap(_.toIntOption).fold(Config.default)(port => Config.default.copy(shardingPort = port)))
    )

  val program: ZIO[Sharding with Scope with Serialization with RedisCommands[Task, String, String], Throwable, Unit] =
    for {
      guild <- Sharding.registerEntity(Guild, behavior)
      user1 <- Random.nextUUID.map(_.toString)
      user2 <- Random.nextUUID.map(_.toString)
      user3 <- Random.nextUUID.map(_.toString)
      _     <- guild.send("guild1")(Join(user1, _)).debug
      _     <- guild.send("guild1")(Join(user2, _)).debug
      _     <- guild.send("guild1")(Join(user3, _)).debug
      _     <- ZIO.never
    } yield ()

  def run: Task[Unit] =
    ZIO
      .scoped(program)
      .provide(
        config,
        ZLayer.succeed(GrpcConfig.default),
        ZLayer.succeed(RedisConfig.default),
        redis,
        StorageRedis.live,
        KryoSerialization.live,
        ShardManagerClient.liveWithSttp,
        GrpcPods.live,
        Sharding.live,
        GrpcShardingService.live.flatMap(_ =>
          ZLayer.scoped(Sharding.registerScoped) // register the node after the gRPC server is running
        )
      )
}
