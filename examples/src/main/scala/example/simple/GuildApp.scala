package example.simple

import com.devsisters.shardcake._
import com.devsisters.shardcake.interfaces._
import example.simple.GuildBehavior._
import example.simple.GuildBehavior.GuildMessage.Join
import zio._
import zio.clock.Clock
import zio.console.Console
import zio.random.Random

object GuildApp extends zio.App {
  val program =
    Sharding.registerEntity(Guild, behavior).mapM { guild =>
      for {
        _ <- guild.send("guild1")(Join("user1", _)).debug
        _ <- guild.send("guild1")(Join("user2", _)).debug
        _ <- guild.send("guild1")(Join("user3", _)).debug
        _ <- guild.send("guild1")(Join("user4", _)).debug
        _ <- guild.send("guild1")(Join("user5", _)).debug
        _ <- guild.send("guild1")(Join("user6", _)).debug
      } yield ()
    }

  val clock    = Clock.live
  val logging  = Console.live >>> Logging.console
  val config   = ZLayer.succeed(Config.default)
  val pods     = ZLayer.succeed(GrpcConfig.default) ++ logging >>> GrpcPods.live
  val client   = config ++ logging >>> ShardManagerClient.liveWithSttp
  val sharding = pods ++ client ++ Storage.memory ++ config ++ clock ++ Random.live ++ logging >>> Sharding.live
  val service  = config ++ sharding ++ clock >+> GrpcShardingService.live
  val layer    = sharding ++ service ++ Serialization.javaSerialization ++ clock

  def run(args: List[String]): URIO[ZEnv, ExitCode] =
    (Sharding.registerManaged *> program).useNow.provideLayer(layer).exitCode
}
