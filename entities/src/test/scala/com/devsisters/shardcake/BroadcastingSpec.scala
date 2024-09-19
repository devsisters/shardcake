package com.devsisters.shardcake

import com.devsisters.shardcake.interfaces.{ Pods, Serialization, Storage }
import zio.{ Config => _, _ }
import zio.test.TestAspect.{ sequential, withLiveClock }
import zio.test._

import scala.util.Success

object BroadcastingSpec extends ZIOSpecDefault {

  private val config = ZLayer.succeed(Config.default)

  def spec: Spec[TestEnvironment with Scope, Any] =
    suite("BroadcastingSpec")(
      test("Send broadcast to entities") {
        ZIO.scoped {
          for {
            _           <- Sharding.registerTopic(IncrementerActor.Incrementer, IncrementerActor.behavior)
            _           <- Sharding.registerScoped
            incrementer <- Sharding.broadcaster(IncrementerActor.Incrementer)
            _           <- incrementer.broadcastDiscard("c1")(IncrementerActor.IncrementerMessage.BroadcastIncrement)
            _           <- Clock.sleep(1 second)
            c1          <- incrementer.broadcast("c1")(IncrementerActor.IncrementerMessage.GetIncrement(_))
          } yield assertTrue(
            c1.values.toList == List(Success(1)) // Here we have just one pod, so there will be just one incrementer
          )
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
      case object BroadcastIncrement                 extends IncrementerMessage
      case class GetIncrement(replier: Replier[Int]) extends IncrementerMessage
    }

    object Incrementer extends TopicType[IncrementerMessage]("incrementer")

    def behavior(topic: String, messages: Dequeue[IncrementerMessage]): RIO[Sharding, Nothing] =
      ZIO.debug(s"Started topic $topic on this pod") *>
        Ref
          .make(0)
          .flatMap(ref =>
            messages.take.flatMap {
              case IncrementerMessage.BroadcastIncrement    =>
                ref.update(_ + 1)
              case IncrementerMessage.GetIncrement(replier) =>
                ref.get.flatMap(replier.reply)
            }.forever
          )
  }
}
