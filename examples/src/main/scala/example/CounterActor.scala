package example

import com.devsisters.sharding.Messenger.Address
import com.devsisters.sharding.interfaces.Serialization
import com.devsisters.sharding.{ EntityType, Messenger, Sharding }
import zio.{ Dequeue, Ref, Task, ZIO, ZLayer }

object CounterActor {
  sealed trait CounterMessage

  object CounterMessage {
    case class GetCounter(replyTo: Address[Int]) extends CounterMessage
    case object IncrementCounter                 extends CounterMessage
    case object DecrementCounter                 extends CounterMessage
  }

  object Counter extends EntityType[CounterMessage]("counter")

  def behavior(sharding: Sharding): (String, Dequeue[CounterMessage]) => Task[Nothing] = { case (_, queue) =>
    Ref
      .make(0)
      .flatMap(state =>
        queue.take.flatMap {
          case CounterMessage.GetCounter(replyTo) => state.get.flatMap(sharding.reply(_, replyTo))
          case CounterMessage.IncrementCounter    => state.update(_ + 1)
          case CounterMessage.DecrementCounter    => state.update(_ - 1)
        }.forever
      )
  }

  val live: ZLayer[Sharding with Serialization, Nothing, Messenger[CounterMessage]] =
    ZLayer.scoped {
      for {
        sharding  <- ZIO.service[Sharding]
        messenger <- sharding.registerEntity(Counter, behavior(sharding))
      } yield messenger
    }
}
