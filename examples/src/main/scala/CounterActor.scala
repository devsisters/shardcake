import CounterActor.CounterMessage.Terminate
import com.devsisters.sharding.Messenger.Address
import com.devsisters.sharding.interfaces.Serialization
import com.devsisters.sharding.{ EntityType, Messenger, Sharding }
import zio.{ Dequeue, Promise, Ref, Task, ZIO, ZLayer }

object CounterActor {
  sealed trait CounterMessage

  object CounterMessage {
    case class GetCounter(replyTo: Address[Int])          extends CounterMessage
    case object IncrementCounter                          extends CounterMessage
    case object DecrementCounter                          extends CounterMessage
    case class Terminate(promise: Promise[Nothing, Unit]) extends CounterMessage
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
          case CounterMessage.Terminate(p)        => p.succeed(()) *> ZIO.interrupt
        }.forever
      )
  }

  val replyTo: CounterMessage => Option[Address[Nothing]] = {
    case CounterMessage.GetCounter(replyTo) => Some(replyTo)
    case _                                  => None
  }

  val live: ZLayer[Sharding with Serialization, Nothing, Messenger[CounterMessage]] =
    ZLayer.scoped {
      for {
        sharding  <- ZIO.service[Sharding]
        messenger <- sharding.registerEntity(Counter, behavior(sharding), replyTo, Terminate)
      } yield messenger
    }
}
