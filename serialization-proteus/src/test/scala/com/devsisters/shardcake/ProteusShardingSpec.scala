package com.devsisters.shardcake

import com.devsisters.shardcake.interfaces.Storage
import com.devsisters.shardcake.proteus.given
import zio.stream.{ SubscriptionRef, ZStream }
import zio.test.TestAspect.{ sequential, withLiveClock }
import zio.test._
import zio.{ Config => _, _ }

/**
 * End-to-end test that exercises the full Sharding + LocalSharding flow using the Proteus
 * backend. Reply types are case classes (Proteus only supports messages / enums at the
 * root, not primitives), so we wrap `Int` in a small `Count` case class.
 *
 * The local-pod shortcut means most of the action goes through the typed reply channel
 * path; setting `simulateRemotePods = true` forces the byte-channel path through Proteus.
 */
object ProteusShardingSpec extends ZIOSpecDefault {
  import ProteusShardingSpec.CounterActor._
  import ProteusShardingSpec.CounterActor.CounterMessage._

  def spec: Spec[TestEnvironment with Scope, Any] =
    suite("ProteusShardingSpec")(
      suite("local-pod shortcut")(tests*).provideShared(
        LocalSharding.live,
        ShardManagerClient.local,
        Storage.memory,
        ZLayer.succeed(Config.default)
      ),
      suite("simulate remote pods (forces proteus serialization on every hop)")(tests*).provideShared(
        LocalSharding.live,
        ShardManagerClient.local,
        Storage.memory,
        ZLayer.succeed(Config.default.copy(simulateRemotePods = true))
      )
    ) @@ sequential @@ withLiveClock

  private val tests: List[Spec[Sharding, Any]] = List(
    test("Send fire-and-forget then ask for the count") {
      ZIO.scoped {
        for {
          _       <- Sharding.registerEntity(Counter, behavior)
          _       <- Sharding.registerScoped
          counter <- Sharding.messenger(Counter)
          _       <- counter.sendDiscard("c1")(IncrementCounter)
          _       <- counter.sendDiscard("c1")(IncrementCounter)
          _       <- counter.sendDiscard("c1")(DecrementCounter)
          _       <- counter.sendDiscard("c2")(IncrementCounter)
          _       <- Clock.sleep(500.millis)
          c1      <- counter.send("c1")(GetCounter.apply)
          c2      <- counter.send("c2")(GetCounter.apply)
        } yield assertTrue(c1.value == 1, c2.value == 1)
      }
    },
    test("Response stream over Proteus") {
      ZIO.scoped {
        for {
          _       <- Sharding.registerEntity(Counter, behavior)
          _       <- Sharding.registerScoped
          counter <- Sharding.messenger(Counter)
          stream  <- counter.sendAndReceiveStream("c1")(StreamingChanges.apply)
          latch   <- Promise.make[Nothing, Unit]
          fiber   <- stream.take(4).tap(_ => latch.succeed(())).runCollect.fork
          _       <- latch.await
          _       <- counter.sendDiscard("c1")(IncrementCounter)
          _       <- counter.sendDiscard("c1")(IncrementCounter)
          _       <- counter.sendDiscard("c1")(DecrementCounter)
          items   <- fiber.join
        } yield assertTrue(items.map(_.value) == Chunk(0, 1, 2, 1))
      }
    },
    // The Counter protocol has two reply-bearing variants (GetCounter and StreamingChanges).
    // Proteus must resolve the right reply codec for the variant carried by `request`.
    test("Multi-variant sendStreamAndReceiveStream over Proteus") {
      ZIO.scoped {
        for {
          _       <- Sharding.registerEntity(Counter, behavior)
          _       <- Sharding.registerScoped
          counter <- Sharding.messenger(Counter)
          latch   <- Promise.make[Nothing, Unit]
          stream  <- counter.sendStreamAndReceiveStream[Count]("c1")(
                       StreamingChanges.apply,
                       ZStream.fromZIO(latch.await).drain ++
                         ZStream.fromIterable(List(IncrementCounter, IncrementCounter, DecrementCounter))
                     )
          items   <- stream.tap(_ => latch.succeed(())).take(4).runCollect
        } yield assertTrue(items.map(_.value) == Chunk(0, 1, 2, 1))
      }
    }
  )

  // Reply payload wrapped in a case class — Proteus needs a message at the root, not a primitive.
  final case class Count(value: Int)

  object CounterActor {
    sealed trait CounterMessage
    object CounterMessage {
      final case class GetCounter(replier: Replier[Count])             extends CounterMessage
      case object IncrementCounter                                     extends CounterMessage
      case object DecrementCounter                                     extends CounterMessage
      final case class StreamingChanges(replier: StreamReplier[Count]) extends CounterMessage
    }

    object Counter extends EntityType[CounterMessage]("counter")

    def behavior(entityId: String, messages: Dequeue[CounterMessage]): RIO[Sharding, Nothing] =
      ZIO.logInfo(s"Started entity $entityId") *>
        SubscriptionRef
          .make(0)
          .flatMap(state =>
            messages.take.flatMap {
              case CounterMessage.GetCounter(replier)       => state.get.flatMap(v => replier.reply(Count(v)))
              case CounterMessage.IncrementCounter          => state.update(_ + 1)
              case CounterMessage.DecrementCounter          => state.update(_ - 1)
              case CounterMessage.StreamingChanges(replier) =>
                replier.replyStream(state.changes.ensuring(state.set(-1)).map(Count(_)))
            }.forever
          )
  }
}
