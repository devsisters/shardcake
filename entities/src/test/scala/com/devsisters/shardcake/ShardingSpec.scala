package com.devsisters.shardcake

import com.devsisters.shardcake.CounterActor.CounterMessage._
import com.devsisters.shardcake.CounterActor._
import com.devsisters.shardcake.Messenger.Replier
import com.devsisters.shardcake.interfaces.{ Pods, Serialization, Storage }
import sttp.client3.UriContext
import zio.test.TestAspect.{ sequential, withLiveClock }
import zio.test._
import zio._

object ShardingSpec extends ZIOSpecDefault {
  def spec: Spec[TestEnvironment with Scope, Any] =
    suite("ShardingSpec")(
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
      },
      test("Cluster singleton") {
        ZIO.scoped {
          for {
            _   <- Sharding.registerScoped
            _   <- Sharding.registerScoped
            p   <- Promise.make[Nothing, Unit]
            _   <- Sharding.registerSingleton("singleton", p.succeed(()) *> ZIO.never)
            res <- p.await
          } yield assertTrue(res == ())
        }
      }
    ).provideShared(
      Sharding.live,
      Serialization.javaSerialization,
      Pods.noop,
      ShardManagerClient.local,
      Storage.memory,
      ZLayer.succeed(Config(10, "localhost", 8888, uri"http://localhost", "1"))
    ) @@ sequential @@ withLiveClock
}

object CounterActor {
  sealed trait CounterMessage

  object CounterMessage {
    case class GetCounter(replier: Replier[Int]) extends CounterMessage
    case object IncrementCounter                 extends CounterMessage
    case object DecrementCounter                 extends CounterMessage
  }

  object Counter extends EntityType[CounterMessage]("counter")

  def behavior(entityId: String, messages: Dequeue[CounterMessage]): RIO[Sharding, Nothing] =
    ZIO.logInfo(s"Started entity $entityId") *>
      Ref
        .make(0)
        .flatMap(state =>
          messages.take.flatMap {
            case CounterMessage.GetCounter(replier) => state.get.flatMap(replier.reply)
            case CounterMessage.IncrementCounter    => state.update(_ + 1)
            case CounterMessage.DecrementCounter    => state.update(_ - 1)
          }.forever
        )
}
