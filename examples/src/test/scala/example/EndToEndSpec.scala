package example

import com.devsisters.shardcake.StorageRedis.Redis
import com.devsisters.shardcake._
import com.devsisters.shardcake.interfaces.PodsHealth
import com.dimafeng.testcontainers.GenericContainer
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.pubsub.PubSub
import example.simple.GuildBehavior
import example.simple.GuildBehavior.Guild
import example.simple.GuildBehavior.GuildMessage.{ Join, Stream, Timeout }
import sttp.client3.UriContext
import zio.{ Config => _, _ }
import zio.Clock.ClockLive
import zio.interop.catz._
import zio.test.Assertion._
import zio.test.TestAspect.{ sequential, withLiveClock }
import zio.test._

import scala.util.Try

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

  val redis: ZLayer[GenericContainer, Throwable, Redis] =
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

  private val config        = ZLayer.succeed(
    Config.default.copy(
      shardManagerUri = uri"http://localhost:8087/api/graphql",
      simulateRemotePods = true,
      sendTimeout = 3 seconds
    )
  )
  private val grpcConfig    = ZLayer.succeed(GrpcConfig.default)
  private val managerConfig = ZLayer.succeed(ManagerConfig.default.copy(apiPort = 8087))
  private val redisConfig   = ZLayer.succeed(RedisConfig.default)

  def spec: Spec[TestEnvironment with Scope, Any] =
    suite("EndToEndSpec")(
      test("Send message to entities") {
        ZIO.scoped {
          for {
            _       <- Sharding.registerEntity(Guild, GuildBehavior.behavior)
            _       <- Sharding.registerScoped
            guild   <- Sharding.messenger(Guild)
            _       <- guild.send("guild1")(Join("user1", _))
            timeout <- guild.send("guild1")(Timeout(_)).exit
            _       <- guild.send("guild1")(Join("user2", _))
            _       <- guild.send("guild1")(Join("user3", _))
            _       <- guild.send("guild1")(Join("user4", _))
            members <- guild.send[Try[Set[String]]]("guild1")(Join("user5", _))
            failure <- guild.send[Try[Set[String]]]("guild1")(Join("user6", _))
            stream  <- guild.sendAndReceiveStream[String]("guild1")(Stream(_))
            res     <- stream.runCollect
          } yield assert(members)(isSuccess(hasSize(equalTo(5)))) &&
            assertTrue(failure.isFailure) &&
            assertTrue(timeout.toTry.isFailure) &&
            assert(res)(hasSize(equalTo(5)))
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
      config,
      grpcConfig,
      managerConfig,
      redisConfig
    ) @@ sequential @@ withLiveClock
}
