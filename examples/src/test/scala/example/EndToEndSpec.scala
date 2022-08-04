package example

import com.devsisters.shardcake.StorageRedis.fs2Stream
import com.devsisters.shardcake._
import com.devsisters.shardcake.interfaces.PodsHealth
import com.dimafeng.testcontainers.GenericContainer
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.pubsub.{ PubSub, PubSubCommands }
import dev.profunktor.redis4cats.{ Redis, RedisCommands }
import example.simple.GuildBehavior
import example.simple.GuildBehavior.Guild
import example.simple.GuildBehavior.GuildMessage.Join
import zio.Clock.ClockLive
import zio._
import zio.interop.catz._
import zio.test.Assertion._
import zio.test.TestAspect.sequential
import zio.test._

object EndToEndSpec extends ZIOSpecDefault {

  val shardManagerServer: ZLayer[ShardManager with ManagerConfig, Throwable, Unit] =
    ZLayer(Server.run.forkDaemon *> ClockLive.sleep(3 seconds).unit)

  val container: ZLayer[Any, Nothing, GenericContainer] =
    ZLayer.scoped {
      ZIO.acquireRelease {
        ZIO.attemptBlocking {
          val container = new GenericContainer(dockerImage = "redis:6.2.5", exposedPorts = Seq(6379))
          container.start()
          container
        }.orDie
      }(container => ZIO.attemptBlocking(container.stop()).orDie)
    }

  val redis: ZLayer[GenericContainer, Throwable, RedisCommands[Task, String, String] with PubSubCommands[
    fs2Stream,
    String,
    String
  ]] =
    ZLayer.scopedEnvironment {
      implicit val runtime: zio.Runtime[Any] = zio.Runtime.default
      implicit val logger: Log[Task]         = new Log[Task] {
        override def debug(msg: => String): Task[Unit] = ZIO.unit
        override def error(msg: => String): Task[Unit] = ZIO.logError(msg)
        override def info(msg: => String): Task[Unit]  = ZIO.logDebug(msg)
      }

      ZIO
        .service[GenericContainer]
        .flatMap(container =>
          (for {
            client   <- RedisClient[Task].from(
                          s"redis://foobared@${container.host}:${container.mappedPort(container.exposedPorts.head)}"
                        )
            commands <- Redis[Task].fromClient(client, RedisCodec.Utf8)
            pubSub   <- PubSub.mkPubSubConnection[Task, String, String](client, RedisCodec.Utf8)
          } yield ZEnvironment(commands, pubSub)).toScopedZIO
        )
    }

  def spec: Spec[TestEnvironment with Scope, Any] =
    suite("EndToEndSpec")(
      test("Send message to entities") {
        ZIO.scoped {
          for {
            _       <- Sharding.registerScoped
            guild   <- Sharding.registerEntity(Guild, GuildBehavior.behavior)
            _       <- guild.send("guild1")(Join("user1", _))
            _       <- guild.send("guild1")(Join("user2", _))
            _       <- guild.send("guild1")(Join("user3", _))
            _       <- guild.send("guild1")(Join("user4", _))
            members <- guild.send("guild1")(Join("user5", _))
            failure <- guild.send("guild1")(Join("user6", _))
          } yield assert(members)(isSuccess(hasSize(equalTo(5)))) && assert(failure)(isFailure(anything))
        }
      }
    ).provideShared(
      Sharding.live,
      KryoSerialization.live,
      GrpcPods.live,
      ShardManagerClient.liveWithSttp,
      StorageRedis.live,
      ShardManager.live,
      PodsHealth.noop,
      GrpcShardingService.live,
      shardManagerServer,
      container,
      redis,
      ZLayer.succeed(Config.default),
      ZLayer.succeed(GrpcConfig.default),
      ZLayer.succeed(ManagerConfig.default),
      ZLayer.succeed(RedisConfig.default)
    ) @@ sequential
}
