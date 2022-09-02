package example.complex

import com.devsisters.shardcake._
import com.devsisters.shardcake.interfaces.Logging
import example.complex.GuildBehavior.GuildMessage.Join
import example.complex.GuildBehavior._
import zio._
import zio.clock.Clock
import zio.random.Random

object GuildApp extends zio.App {
  val config =
    system
      .env("port")
      .map(_.flatMap(_.toIntOption).fold(Config.default)(port => Config.default.copy(shardingPort = port)))
      .toLayer

  val program =
    (Sharding.registerEntity(Guild, behavior) *> Sharding.registerManaged).use { _ =>
      Sharding.messenger(Guild).map { guild =>
        for {
          user1 <- random.nextUUID.map(_.toString)
          user2 <- random.nextUUID.map(_.toString)
          user3 <- random.nextUUID.map(_.toString)
          _     <- guild.send("guild1")(Join(user1, _)).debug
          _     <- guild.send("guild1")(Join(user2, _)).debug
          _     <- guild.send("guild1")(Join(user3, _)).debug
          _     <- ZIO.never
        } yield ()
      }
    }

  val clock    = Clock.live
  val logging  = Logging.debug
  val pods     = ZLayer.succeed(GrpcConfig.default) ++ logging >>> GrpcPods.live
  val client   = config ++ logging >>> ShardManagerClient.liveWithSttp
  val storage  = ZLayer.succeed(RedisConfig.default) ++ redis >>> StorageRedis.live
  val sharding =
    pods ++ client ++ storage ++ config ++ clock ++ Random.live ++ logging ++ KryoSerialization.live >+> Sharding.live
  val service  = config ++ sharding ++ clock >+> GrpcShardingService.live
  val layer    = sharding ++ service ++ redis

  def run(args: List[String]): URIO[ZEnv, ExitCode] =
    program.provideLayer(layer).exitCode
}
