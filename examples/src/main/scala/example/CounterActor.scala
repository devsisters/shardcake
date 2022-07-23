package example

import com.devsisters.shardcake.Messenger.Replier
import com.devsisters.shardcake.interfaces.Serialization
import com.devsisters.shardcake.{ EntityType, Messenger, Sharding }
import zio.{ Dequeue, RIO, Ref, ZIO, ZLayer }

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

  val live: ZLayer[Sharding with Serialization, Nothing, Messenger[CounterMessage]] =
    ZLayer.scoped {
      ZIO.serviceWithZIO[Sharding](_.registerEntity(Counter, behavior))
    }
}
