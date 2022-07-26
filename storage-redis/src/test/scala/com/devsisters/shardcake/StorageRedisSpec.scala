package com.devsisters.shardcake

import com.devsisters.shardcake.StorageRedis.fs2Stream
import com.devsisters.shardcake.interfaces.Storage
import com.dimafeng.testcontainers.GenericContainer
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.pubsub.{ PubSub, PubSubCommands }
import dev.profunktor.redis4cats.{ Redis, RedisCommands }
import zio.Clock.ClockLive
import zio._
import zio.interop.catz._
import zio.stream.ZStream
import zio.test.TestAspect.sequential
import zio.test._

object StorageRedisSpec extends ZIOSpecDefault {
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
    suite("StorageRedisSpec")(
      test("save and get pods") {
        val expected = List(Pod(PodAddress("host1", 1), "1.0.0"), Pod(PodAddress("host2", 2), "2.0.0"))
          .map(p => p.address -> p)
          .toMap
        for {
          _      <- ZIO.serviceWithZIO[Storage](_.savePods(expected))
          actual <- ZIO.serviceWithZIO[Storage](_.getPods)
        } yield assertTrue(expected == actual)
      },
      test("save and get assignments") {
        val expected = Map(1 -> Some(PodAddress("host1", 1)), 2 -> None)
        for {
          _      <- ZIO.serviceWithZIO[Storage](_.saveAssignments(expected))
          actual <- ZIO.serviceWithZIO[Storage](_.getAssignments)
        } yield assertTrue(expected == actual)
      },
      test("assignments stream") {
        val expected = Map(1 -> Some(PodAddress("host1", 1)), 2 -> None)
        for {
          p      <- Promise.make[Nothing, Map[Int, Option[PodAddress]]]
          _      <- ZStream.serviceWithStream[Storage](_.assignmentsStream).runForeach(p.succeed(_)).fork
          _      <- ClockLive.sleep(1 second)
          _      <- ZIO.serviceWithZIO[Storage](_.saveAssignments(expected))
          actual <- p.await
        } yield assertTrue(expected == actual)
      }
    ).provideCustomLayerShared(
      container >>> redis ++ ZLayer.succeed(RedisConfig.default) >>> StorageRedis.live
    ) @@ sequential
}
