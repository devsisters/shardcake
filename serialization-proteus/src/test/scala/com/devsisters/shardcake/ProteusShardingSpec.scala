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
    },
    // Regression test for shardcake's pendingReplies overwrite bug with Proteus's
    // per-variant reply encoder. The first message carries the StreamReplier; the
    // follow-ups (IncrementCounter / DecrementCounter — no Replier field) are emitted
    // eagerly, ahead of any reply being received. Without the fix, the receiver's
    // `processBinary` would re-register the pendingReply slot with `fallbackEncode`
    // (empty bytes) before the actor's `replyStream` captures the real encoder, and the
    // sender would then fail to decode replies with "OneOf field absent".
    //
    // Uses `Reply` (sealed trait, required OneOf field) so an empty payload from
    // `fallbackEncode` is a hard decode failure rather than a silent default value.
    test("Eager non-Replier follow-ups don't clobber the reply encoder") {
      ZIO.scoped {
        for {
          _       <- Sharding.registerEntity(Counter, behavior)
          _       <- Sharding.registerScoped
          counter <- Sharding.messenger(Counter)
          stream  <- counter.sendStreamAndReceiveStream[Reply]("c1")(
                       StreamReply.apply,
                       ZStream.fromIterable(List(IncrementCounter, IncrementCounter, DecrementCounter))
                     )
          // Take only the initial ack — `state.changes` subscription timing is unreliable
          // once eager follow-ups race ahead. The bug shows up as a decode failure on the
          // very first reply, so one element is enough to assert correctness.
          first   <- stream.take(1).runCollect.timeoutFail("timeout")(10.seconds)
        } yield assertTrue(first.size == 1 && first.head == Reply.Ack())
      }
    }
  )

  // Reply payload wrapped in a case class — Proteus needs a message at the root, not a primitive.
  final case class Count(value: Int)

  // Sealed-trait reply used by the regression test. A required OneOf field means an
  // empty payload (what `fallbackEncode` would produce) is a hard decode failure.
  sealed trait Reply
  object Reply {
    final case class Ack()             extends Reply
    final case class Snapshot(v: Int)  extends Reply
  }

  object CounterActor {
    sealed trait CounterMessage
    object CounterMessage {
      final case class GetCounter(replier: Replier[Count])             extends CounterMessage
      case object IncrementCounter                                     extends CounterMessage
      case object DecrementCounter                                     extends CounterMessage
      final case class StreamingChanges(replier: StreamReplier[Count]) extends CounterMessage
      // Pushes a deterministic `Ack` as the first reply, then a `Snapshot` per
      // Increment/Decrement processed by this actor while the stream is open.
      final case class StreamReply(replier: StreamReplier[Reply])      extends CounterMessage
    }

    object Counter extends EntityType[CounterMessage]("counter")

    def behavior(entityId: String, messages: Dequeue[CounterMessage]): RIO[Sharding, Nothing] =
      ZIO.logInfo(s"Started entity $entityId") *>
        (SubscriptionRef.make(0) <*> Ref.make[Option[Queue[Reply]]](None)).flatMap { case (state, replyQueueRef) =>
          messages.take.flatMap {
            case CounterMessage.GetCounter(replier)       => state.get.flatMap(v => replier.reply(Count(v)))
            case CounterMessage.IncrementCounter          =>
              state.updateAndGet(_ + 1).flatMap(v =>
                replyQueueRef.get.flatMap(ZIO.foreachDiscard(_)(_.offer(Reply.Snapshot(v))))
              )
            case CounterMessage.DecrementCounter          =>
              state.updateAndGet(_ - 1).flatMap(v =>
                replyQueueRef.get.flatMap(ZIO.foreachDiscard(_)(_.offer(Reply.Snapshot(v))))
              )
            case CounterMessage.StreamingChanges(replier) =>
              replier.replyStream(state.changes.ensuring(state.set(-1)).map(Count(_)))
            case CounterMessage.StreamReply(replier)      =>
              // Forces eager follow-ups to reach `processBinary` before the actor subscribes.
              ZIO.sleep(200.millis) *>
                (for {
                  q <- Queue.unbounded[Reply]
                  _ <- replyQueueRef.set(Some(q))
                  _ <- replier.replyStream(ZStream.fromQueueWithShutdown(q))
                  _ <- q.offer(Reply.Ack())
                } yield ())
          }.forever
        }
  }
}
