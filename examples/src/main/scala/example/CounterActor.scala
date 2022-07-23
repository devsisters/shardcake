package example

import com.devsisters.shardcake.Messenger.Replier
import com.devsisters.shardcake.interfaces.Serialization
import com.devsisters.shardcake.{ EntityType, Messenger, Sharding }
import zio.{ Dequeue, RIO, Ref, ZIO, ZLayer }

object CounterActor {
  sealed trait CounterMessage

  object CounterMessage {
    case class GetCounter(replyTo: Replier[Int]) extends CounterMessage
    case object IncrementCounter                 extends CounterMessage
    case object DecrementCounter                 extends CounterMessage
  }

  object Counter extends EntityType[CounterMessage]("counter")

  val behavior: (String, Dequeue[CounterMessage]) => RIO[Sharding, Nothing] = { case (_, queue) =>
    Ref
      .make(0)
      .flatMap(state =>
        queue.take.flatMap {
          case CounterMessage.GetCounter(replyTo) => state.get.flatMap(replyTo.reply)
          case CounterMessage.IncrementCounter    => state.update(_ + 1)
          case CounterMessage.DecrementCounter    => state.update(_ - 1)
        }.forever
      )
  }

  val live: ZLayer[Sharding with Serialization, Nothing, Messenger[CounterMessage]] =
    ZLayer.scoped {
      ZIO.serviceWithZIO[Sharding](_.registerEntity(Counter, behavior))
    }
}
