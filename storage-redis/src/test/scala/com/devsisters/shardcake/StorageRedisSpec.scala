package com.devsisters.shardcake

import com.devsisters.shardcake.StorageRedis.Redis
import com.devsisters.shardcake.interfaces.Storage
import com.dimafeng.testcontainers.GenericContainer
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.pubsub.PubSub
import zio._
import zio.blocking.Blocking
import zio.clock.Clock
import zio.duration._
import zio.interop.catz._
import zio.stream.ZStream
import zio.test.TestAspect.sequential
import zio.test._

object StorageRedisSpec extends DefaultRunnableSpec {
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

  def spec: ZSpec[Blocking, Throwable] =
    suite("StorageRedisSpec")(
      testM("save and get pods") {
        val expected = List(Pod(PodAddress("host1", 1), "1.0.0"), Pod(PodAddress("host2", 2), "2.0.0"))
          .map(p => p.address -> p)
          .toMap
        for {
          _      <- ZIO.serviceWith[Storage](_.savePods(expected))
          actual <- ZIO.serviceWith[Storage](_.getPods)
        } yield assertTrue(expected == actual)
      },
      testM("save and get assignments") {
        val expected = Map(1 -> Some(PodAddress("host1", 1)), 2 -> None)
        for {
          _      <- ZIO.serviceWith[Storage](_.saveAssignments(expected))
          actual <- ZIO.serviceWith[Storage](_.getAssignments)
        } yield assertTrue(expected == actual)
      },
      testM("assignments stream") {
        val expected = Map(1 -> Some(PodAddress("host1", 1)), 2 -> None)
        for {
          p      <- Promise.make[Nothing, Map[Int, Option[PodAddress]]]
          _      <- ZStream.serviceWithStream[Storage](_.assignmentsStream).foreach(p.succeed).fork
          _      <- Clock.Service.live.sleep(1 second)
          _      <- ZIO.serviceWith[Storage](_.saveAssignments(expected))
          actual <- p.await
        } yield assertTrue(expected == actual)
      }
    ).provideLayerShared(
      (container >>> redis ++ ZLayer.succeed(RedisConfig.default) >>> StorageRedis.live).mapError(TestFailure.fail)
    ) @@ sequential
}
