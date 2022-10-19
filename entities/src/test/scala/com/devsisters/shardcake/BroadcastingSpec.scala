package com.devsisters.shardcake

import com.devsisters.shardcake.interfaces.{ Logging, Pods, Serialization, Storage }
import zio._
import zio.clock.Clock
import zio.duration._
import zio.random.Random
import zio.test.TestAspect.sequential
import zio.test._
import zio.test.environment.TestEnvironment

import scala.util.Success

object BroadcastingSpec extends DefaultRunnableSpec {

  private val layer =
    (Clock.live ++ Random.live ++ ZLayer.succeed(Config.default) >+>
      ShardManagerClient.local ++ Logging.debug ++ Pods.noop ++ Storage.memory ++ Serialization.javaSerialization >+>
      Sharding.live).mapError(TestFailure.fail)

  def spec: ZSpec[TestEnvironment, Throwable] =
    suite("BroadcastingSpec")(
      testM("Send broadcast to entities") {
        (Sharding.registerTopic(IncrementerActor.Incrementer, IncrementerActor.behavior) *>
          Sharding.registerManaged).use { _ =>
          for {
            incrementer <- Sharding.broadcaster(IncrementerActor.Incrementer)
            _           <- incrementer.broadcastDiscard("c1")(IncrementerActor.IncrementerMessage.BroadcastIncrement)
            _           <- clock.sleep(1 second)
            c1          <- incrementer.broadcast("c1")(IncrementerActor.IncrementerMessage.GetIncrement(_))
          } yield assertTrue(
            c1.values.toList == List(Success(1)) // Here we have just one pod, so there will be just one incrementer
          )
        }
      }
    ).provideLayerShared(layer) @@ sequential

  object IncrementerActor {
    sealed trait IncrementerMessage

    object IncrementerMessage {
      case object BroadcastIncrement                 extends IncrementerMessage
      case class GetIncrement(replier: Replier[Int]) extends IncrementerMessage
    }

    object Incrementer extends TopicType[IncrementerMessage]("incrementer")

    def behavior(topic: String, messages: Dequeue[IncrementerMessage]): RIO[Has[Sharding], Nothing] =
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
