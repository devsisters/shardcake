package example.simple

import com.devsisters.shardcake._
import com.devsisters.shardcake.interfaces._
import example.simple.GuildBehavior._
import example.simple.GuildBehavior.GuildMessage.Join
import zio._

object GuildApp extends ZIOAppDefault {
  val program =
    for {
      _     <- Sharding.registerEntity(Guild, behavior)
      _     <- Sharding.registerScoped
      guild <- Sharding.messenger(Guild)
      _     <- guild.send("guild1")(Join("user1", _)).debug
      _     <- guild.send("guild1")(Join("user2", _)).debug
      _     <- guild.send("guild1")(Join("user3", _)).debug
      _     <- guild.send("guild1")(Join("user4", _)).debug
      _     <- guild.send("guild1")(Join("user5", _)).debug
      _     <- guild.send("guild1")(Join("user6", _)).debug
    } yield ()

  def run: Task[Unit] =
    ZIO
      .scoped(program)
      .provide(
        ZLayer.succeed(Config.default),
        ZLayer.succeed(GrpcConfig.default),
        Serialization.javaSerialization,
        Storage.memory,
        ShardManagerClient.liveWithSttp,
        GrpcPods.live,
        Sharding.live,
        GrpcShardingService.live
      )
}
