package example.simple

import com.devsisters.shardcake._
import com.devsisters.shardcake.interfaces._
import example.simple.GuildBehavior.GuildMessage.Join
import example.simple.GuildBehavior._
import zio._
import zio.clock.Clock
import zio.random.Random

object GuildApp extends zio.App {
  val program =
    Sharding.registerEntity(Guild, behavior).use { _ =>
      Sharding.messenger(Guild).map { guild =>
        for {
          _ <- guild.send("guild1")(Join("user1", _)).debug
          _ <- guild.send("guild1")(Join("user2", _)).debug
          _ <- guild.send("guild1")(Join("user3", _)).debug
          _ <- guild.send("guild1")(Join("user4", _)).debug
          _ <- guild.send("guild1")(Join("user5", _)).debug
          _ <- guild.send("guild1")(Join("user6", _)).debug
        } yield ()
      }
    }

  val clock    = Clock.live
  val logging  = Logging.debug
  val config   = ZLayer.succeed(Config.default)
  val pods     = ZLayer.succeed(GrpcConfig.default) ++ logging >>> GrpcPods.live
  val client   = config ++ logging >>> ShardManagerClient.liveWithSttp
  val sharding =
    pods ++ client ++ Storage.memory ++ config ++ clock ++ Random.live ++ logging ++ Serialization.javaSerialization >+> Sharding.live
  val service  = config ++ sharding ++ clock >+> GrpcShardingService.live
  val layer    = sharding ++ service

  def run(args: List[String]): URIO[ZEnv, ExitCode] =
    Sharding.registerManaged.use(_ => program).provideLayer(layer).exitCode
}
