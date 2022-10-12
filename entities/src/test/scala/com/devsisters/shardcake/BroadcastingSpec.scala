package com.devsisters.shardcake

import com.devsisters.shardcake.CounterActor.CounterMessage._
import com.devsisters.shardcake.CounterActor._
import com.devsisters.shardcake.interfaces.{ Pods, Serialization, Storage }
import zio._
import zio.test.TestAspect.{ sequential, withLiveClock }
import zio.test._

object BroadcastingSpec extends ZIOSpecDefault {

  private val config = ZLayer.succeed(
    Config.default.copy(
      ```simulateRemotePods = true```
    )
  )

  def spec: Spec[TestEnvironment with Scope, Any] =
    suite("BroadcastingSpec")(
      test("Send broadcast to entities") {

        ZIO.scoped {
          for {
            _           <- Sharding.registerEntity(Counter, behavior)
            _           <- Sharding.registerEntity(IncrementerActor.Incrementer, IncrementerActor.behavior)
            _           <- Sharding.registerScoped
            counter     <- Sharding.messenger(Counter)
            incrementer <- Sharding.broadcaster(IncrementerActor.Incrementer)
            _           <- incrementer.broadcastDiscard("c1")(IncrementerActor.IncrementerMessage.BroadcastIncrement)
            _           <- Clock.sleep(1 second)
            c1          <- counter.send("c1")(GetCounter.apply)
          } yield assertTrue(c1 == 1) // Here we have just one pod, so there will be just one incrementer
        }
      }
    ).provideShared(
      Sharding.live,
      Serialization.javaSerialization,
      Pods.noop,
      ShardManagerClient.local,
      Storage.memory,
      config
    ) @@ sequential @@ withLiveClock

  object IncrementerActor {
    sealed trait IncrementerMessage

    object IncrementerMessage {
      case object BroadcastIncrement extends IncrementerMessage
    }

    object Incrementer extends EntityType[IncrementerMessage]("incrementer")

    def behavior(topic: String, messages: Dequeue[IncrementerMessage]): RIO[Sharding, Nothing] =
      ZIO.logInfo(s"Started topic $topic on this pod") *>
        Sharding
          .messenger(Counter)
          .flatMap(counter =>
            messages.take.flatMap { case IncrementerMessage.BroadcastIncrement =>
              counter.sendDiscard("c1")(IncrementCounter)
            }.forever
          )
  }
}
