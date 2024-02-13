package com.devsisters.shardcake

import com.devsisters.shardcake.interfaces.Storage
import com.dimafeng.testcontainers.GenericContainer
import org.redisson.Redisson
import org.redisson.config.{ Config => RedissonConfig }
import org.redisson.api.RedissonClient
import zio.Clock.ClockLive
import zio._
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

  val redis: ZLayer[GenericContainer, Throwable, RedissonClient] =
    ZLayer {
      for {
        container     <- ZIO.service[GenericContainer]
        uri            = s"redis://foobared@${container.host}:${container.mappedPort(container.exposedPorts.head)}"
        redissonConfig = new RedissonConfig()
        _              = redissonConfig.useSingleServer().setAddress(uri)
        client         = Redisson.create(redissonConfig)
      } yield client
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
    ).provideLayerShared(
      container >>> redis ++ ZLayer.succeed(RedisConfig.default) >>> StorageRedis.live
    ) @@ sequential
}
