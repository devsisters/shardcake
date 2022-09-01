package com.devsisters.shardcake

import com.devsisters.shardcake.CounterActor.CounterMessage._
import com.devsisters.shardcake.CounterActor._
import com.devsisters.shardcake.Messenger.Replier
import com.devsisters.shardcake.interfaces.{ Logging, Pods, Serialization, Storage }
import zio._
import zio.clock.Clock
import zio.console.Console
import zio.duration._
import zio.random.Random
import zio.test.TestAspect.sequential
import zio.test._
import zio.test.environment.TestEnvironment

object ShardingSpec extends DefaultRunnableSpec {

  private val layer =
    (Clock.live ++ Random.live ++ Console.live ++ ZLayer.succeed(Config.default) >+>
      ShardManagerClient.local ++ Logging.console ++ Pods.noop ++ Storage.memory >+>
      Sharding.live ++ Serialization.javaSerialization).mapError(TestFailure.fail)

  def spec: ZSpec[TestEnvironment, Throwable] =
    suite("ShardingSpec")(
      testM("Send message to entities") {
        (Sharding.registerManaged *> Sharding.registerEntity(Counter, behavior)).use { counter =>
          for {
            _  <- counter.sendDiscard("c1")(IncrementCounter)
            _  <- counter.sendDiscard("c1")(DecrementCounter)
            _  <- counter.sendDiscard("c1")(IncrementCounter)
            _  <- counter.sendDiscard("c1")(IncrementCounter)
            _  <- counter.sendDiscard("c2")(IncrementCounter)
            _  <- clock.sleep(1 second)
            c1 <- counter.send("c1")(GetCounter.apply)
            c2 <- counter.send("c2")(GetCounter.apply)
          } yield assertTrue(c1 == 2) && assertTrue(c2 == 1)
        }
      },
      testM("Cluster singleton") {
        Sharding.registerManaged.use(_ =>
          for {
            p   <- Promise.make[Nothing, Unit]
            _   <- Sharding.registerSingleton("singleton", p.succeed(()) *> ZIO.never)
            res <- p.await
          } yield assertTrue(res == ())
        )
      }
    ).provideLayerShared(layer) @@ sequential
}

object CounterActor {
  sealed trait CounterMessage

  object CounterMessage {
    case class GetCounter(replier: Replier[Int]) extends CounterMessage
    case object IncrementCounter                 extends CounterMessage
    case object DecrementCounter                 extends CounterMessage
  }

  object Counter extends EntityType[CounterMessage]("counter")

  def behavior(entityId: String, messages: Dequeue[CounterMessage]): RIO[Has[Sharding], Nothing] =
    ZIO.debug(s"Started entity $entityId") *>
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
