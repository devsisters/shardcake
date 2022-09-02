package example

import com.devsisters.shardcake.StorageRedis.Redis
import com.devsisters.shardcake._
import com.devsisters.shardcake.interfaces.{ Logging, PodsHealth }
import com.dimafeng.testcontainers.GenericContainer
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.pubsub.PubSub
import example.simple.GuildBehavior
import example.simple.GuildBehavior.Guild
import example.simple.GuildBehavior.GuildMessage.Join
import sttp.client3.UriContext
import zio._
import zio.blocking.Blocking
import zio.clock.Clock
import zio.console.Console
import zio.duration._
import zio.interop.catz._
import zio.random.Random
import zio.test.Assertion._
import zio.test.TestAspect.sequential
import zio.test._

import scala.util.Try

object EndToEndSpec extends DefaultRunnableSpec {

  val shardManagerServer
    : ZLayer[Has[ShardManager] with Has[ManagerConfig] with Clock with Console, Throwable, Has[Unit]] =
    (Server.run.forkDaemon *> Clock.Service.live.sleep(3 seconds).unit).toLayer

  val container: ZLayer[Blocking, Nothing, Has[GenericContainer]] =
    blocking.effectBlocking {
      val container = new GenericContainer(dockerImage = "redis:6.2.5", exposedPorts = Seq(6379))
      container.start()
      container
    }.orDie.toManaged(container => blocking.effectBlocking(container.stop()).orDie).toLayer

  val redis: ZLayer[Has[GenericContainer], Throwable, Redis] = {
    implicit val runtime: zio.Runtime[Clock with Blocking] = zio.Runtime.default
    implicit val logger: Log[Task]                         = new Log[Task] {
      override def debug(msg: => String): Task[Unit] = ZIO.unit
      override def error(msg: => String): Task[Unit] = ZIO.debug(msg)
      override def info(msg: => String): Task[Unit]  = ZIO.debug(msg)
    }

    ZManaged
      .service[GenericContainer]
      .flatMap(container =>
        (for {
          client   <- RedisClient[Task].from(
                        s"redis://foobared@${container.host}:${container.mappedPort(container.exposedPorts.head)}"
                      )
          commands <- Redis[Task].fromClient(client, RedisCodec.Utf8)
          pubSub   <- PubSub.mkPubSubConnection[Task, String, String](client, RedisCodec.Utf8)
        } yield Has.allOf(commands, pubSub)).toManagedZIO
      )
      .toLayerMany
  }

  val config        = ZLayer.succeed(Config.default.copy(shardManagerUri = uri"http://localhost:8087/api/graphql"))
  val grpcConfig    = ZLayer.succeed(GrpcConfig.default)
  val managerConfig = ZLayer.succeed(ManagerConfig.default.copy(apiPort = 8087))
  val redisConfig   = ZLayer.succeed(RedisConfig.default)

  val clock          = Clock.live
  val logging        = Logging.debug
  val pods           = grpcConfig ++ logging >>> GrpcPods.live
  val client         = config ++ logging >>> ShardManagerClient.liveWithSttp
  val redisContainer = Blocking.live >>> container >>> redis
  val storage        = redisConfig ++ redisContainer >>> StorageRedis.live
  val sharding       =
    pods ++ client ++ storage ++ config ++ clock ++ Random.live ++ logging ++ KryoSerialization.live >+> Sharding.live
  val service        = config ++ sharding ++ clock >+> GrpcShardingService.live
  val health         = pods >>> PodsHealth.local
  val manager        = health ++ pods ++ storage ++ managerConfig ++ logging ++ clock >>> ShardManager.live
  val managerServer  = manager ++ managerConfig ++ clock ++ Console.live >>> shardManagerServer
  val layer          = sharding ++ service ++ redisContainer ++ managerServer

  def spec =
    suite("EndToEndSpec")(
      testM("Send message to entities") {
        (Sharding.registerEntity(Guild, GuildBehavior.behavior) *> Sharding.registerManaged).use { _ =>
          for {
            guild   <- Sharding.messenger(Guild)
            _       <- guild.send("guild1")(Join("user1", _))
            _       <- guild.send("guild1")(Join("user2", _))
            _       <- guild.send("guild1")(Join("user3", _))
            _       <- guild.send("guild1")(Join("user4", _))
            members <- guild.send[Try[Set[String]]]("guild1")(Join("user5", _))
            failure <- guild.send[Try[Set[String]]]("guild1")(Join("user6", _))
          } yield assert(members)(isSuccess(hasSize(equalTo(5)))) && assertTrue(failure.isFailure)
        }
      }
    ).provideLayerShared(layer.mapError(TestFailure.fail)) @@ sequential
}
