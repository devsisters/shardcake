package com.devsisters.shardcake

import com.devsisters.shardcake.CounterActor.CounterMessage._
import com.devsisters.shardcake.CounterActor._
import com.devsisters.shardcake.Messenger.Replier
import com.devsisters.shardcake.ShardingSpec.layer
import com.devsisters.shardcake.interfaces.{ Logging, Pods, Serialization, Storage }
import zio._
import zio.clock.Clock
import zio.duration._
import zio.random.Random
import zio.test.TestAspect.sequential
import zio.test._
import zio.test.environment.TestEnvironment

object BroadcastingSpec extends DefaultRunnableSpec {

  private val layer =
    (Clock.live ++ Random.live ++ ZLayer.succeed(Config.default.copy(entityMaxIdleTime = 2 seconds)) >+>
      ShardManagerClient.local ++ Logging.debug ++ Pods.noop ++ Storage.memory ++ Serialization.javaSerialization >+>
      Sharding.live).mapError(TestFailure.fail)

  def spec: ZSpec[TestEnvironment, Throwable] =
    suite("BroadcastingSpec")(
      testM("Send broadcast to entities") {
        (Sharding.registerEntity(Counter, behavior) *> Sharding.registerTopic(
          IncrementerActor.Incrementer,
          IncrementerActor.behavior
        ) *> Sharding.registerManaged).use { _ =>
          for {
            counter     <- Sharding.messenger(Counter)
            incrementer <- Sharding.broadcaster(IncrementerActor.Incrementer)
            _           <- incrementer.broadcast("c1")(IncrementerActor.IncrementerMessage.BroadcastIncrement)
            _           <- clock.sleep(1 second)
            c1          <- counter.send("c1")(GetCounter.apply)
          } yield assertTrue(c1 == 1) // Here we have just one pod, so there will be just one incrementer
        }
      }
    ).provideLayerShared(layer) @@ sequential

  object IncrementerActor {
    sealed trait IncrementerMessage

    object IncrementerMessage {
      case object BroadcastIncrement extends IncrementerMessage
    }

    object Incrementer extends TopicType[IncrementerMessage]("incrementer")

    def behavior(topic: String, messages: Dequeue[IncrementerMessage]): RIO[Has[Sharding], Nothing] =
      ZIO.debug(s"Started topic $topic on this pod") *>
        Sharding
          .messenger(Counter)
          .flatMap(counter =>
            messages.take.flatMap { case IncrementerMessage.BroadcastIncrement =>
              counter.sendDiscard("c1")(IncrementCounter)
            }.forever
          )
  }
}
