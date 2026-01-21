package example

import com.devsisters.shardcake.StorageRedis.Redis
import com.devsisters.shardcake._
import com.devsisters.shardcake.interfaces.PodsHealth
import com.devsisters.shardcake.UpickleSerialization._
import com.dimafeng.testcontainers.GenericContainer
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.pubsub.PubSub
import example.mailbox.MailboxBehavior
import example.mailbox.MailboxBehavior.Mailbox
import example.mailbox.MailboxBehavior.MailboxMessage.{ DeleteLetter, GetLetters, ReadLetter, SendLetter }
import sttp.client4.UriContext
import zio.{ Config => _, _ }
import zio.Clock.ClockLive
import zio.interop.catz._
import zio.test.Assertion._
import zio.test.TestAspect.{ sequential, withLiveClock }
import zio.test._

object MailboxEndToEndSpec extends ZIOSpecDefault {

  val shardManagerServer: ZLayer[ShardManager with ManagerConfig, Throwable, Unit] =
    ZLayer(Server.run().forkDaemon *> ClockLive.sleep(3 seconds).unit)

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
      shardManagerUri = uri"http://localhost:8088/api/graphql",
      simulateRemotePods = true,
      sendTimeout = 3 seconds
    )
  )
  private val grpcConfig    = ZLayer.succeed(GrpcConfig.default)
  private val managerConfig = ZLayer.succeed(ManagerConfig.default.copy(apiPort = 8088))
  private val redisConfig   = ZLayer.succeed(RedisConfig.default)

  def spec: Spec[TestEnvironment with Scope, Any] =
    suite("MailboxEndToEndSpec")(
      test("Send and receive letters with upickle serialization") {
        ZIO.scoped {
          for {
            _       <- Sharding.registerEntity(Mailbox, MailboxBehavior.behavior)
            _       <- Sharding.registerScoped
            mailbox <- Sharding.messenger(Mailbox)

            letter1 <-
              mailbox.send[Option[MailboxBehavior.Letter]]("mailbox1")(SendLetter("user1", "Hello from user1!", _))
            letter2 <- mailbox.send[Option[MailboxBehavior.Letter]]("mailbox1")(SendLetter("user2", "Hi there!", _))
            letter3 <- mailbox.send[Option[MailboxBehavior.Letter]]("mailbox1")(SendLetter("user3", "How are you?", _))

            letters <- mailbox.send[List[MailboxBehavior.Letter]]("mailbox1")(GetLetters(_))

            readLetter <- letter1.map(_.id) match {
                            case Some(id) => mailbox.send[Option[MailboxBehavior.Letter]]("mailbox1")(ReadLetter(id, _))
                            case None     => ZIO.none
                          }
            deleted    <- letter2.map(_.id) match {
                            case Some(id) => mailbox.send[Boolean]("mailbox1")(DeleteLetter(id, _))
                            case None     => ZIO.succeed(false)
                          }

            lettersAfterDelete <- mailbox.send[List[MailboxBehavior.Letter]]("mailbox1")(GetLetters(_))

            // Test different mailbox
            letter4  <-
              mailbox.send[Option[MailboxBehavior.Letter]]("mailbox2")(SendLetter("user4", "Message to mailbox2", _))
            letters2 <- mailbox.send[List[MailboxBehavior.Letter]]("mailbox2")(GetLetters(_))

          } yield assert(letter1)(isSome) &&
            assert(letter2)(isSome) &&
            assert(letter3)(isSome) &&
            assert(letters)(hasSize(equalTo(3))) &&
            assertTrue(readLetter.zip(letter1).exists { case (a, b) => a == b }, deleted) &&
            assert(lettersAfterDelete)(hasSize(equalTo(2))) &&
            assert(letter4)(isSome) &&
            assert(letters2)(hasSize(equalTo(1)))
        }
      }
    ).provideShared(
      Sharding.live,
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
