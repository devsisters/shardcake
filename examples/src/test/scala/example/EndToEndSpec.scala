package example

import CounterActor.CounterMessage._
import CounterActor._
import com.devsisters.shardcake.StorageRedis.fs2Stream
import com.devsisters.shardcake._
import com.devsisters.shardcake.interfaces.PodsHealth
import com.dimafeng.testcontainers.GenericContainer
import dev.profunktor.redis4cats.{ Redis, RedisCommands }
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.pubsub.{ PubSub, PubSubCommands }
import sttp.client3.UriContext
import zio.Clock.ClockLive
import zio.interop.catz._
import zio.test.TestAspect.{ sequential, withLiveClock }
import zio.test._
import zio._

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
            counter <- Sharding.registerEntity(Counter, behavior)
            _       <- counter.sendDiscard("c1")(IncrementCounter)
            _       <- counter.sendDiscard("c1")(DecrementCounter)
            _       <- counter.sendDiscard("c1")(IncrementCounter)
            _       <- counter.sendDiscard("c1")(IncrementCounter)
            _       <- counter.sendDiscard("c2")(IncrementCounter)
            _       <- Clock.sleep(1 second)
            c1      <- counter.send("c1")(GetCounter.apply)
            c2      <- counter.send("c2")(GetCounter.apply)
          } yield assertTrue(c1 == 2) && assertTrue(c2 == 1)
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
      ZLayer.succeed(Config(10, "localhost", 8888, uri"http://localhost:8080/api/graphql", "1")),
      ZLayer.succeed(GrpcConfig(32 * 1024 * 1024)),
      ZLayer.succeed(ManagerConfig(10, 8080))
    ) @@ sequential @@ withLiveClock
}
