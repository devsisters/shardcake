import CounterActor.CounterMessage
import CounterActor.CounterMessage._
import com.devsisters.sharding._
import com.devsisters.sharding.interfaces.{ Pods, Serialization }
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.pubsub.PubSub
import sttp.client3.UriContext
import zio._
import zio.interop.catz._

object MainApp extends ZIOAppDefault {
  private val redis =
    ZLayer.scopedEnvironment {
      implicit val runtime: zio.Runtime[Any] = zio.Runtime.default
      implicit val logger: Log[Task]         = new Log[Task] {
        override def debug(msg: => String): Task[Unit] = ZIO.unit
        override def error(msg: => String): Task[Unit] = ZIO.logError(msg)
        override def info(msg: => String): Task[Unit]  = ZIO.logInfo(msg)
      }

      (for {
        client   <- RedisClient[Task].from("redis://localhost")
        commands <- Redis[Task].fromClient(client, RedisCodec.Utf8)
        pubSub   <- PubSub.mkPubSubConnection[Task, String, String](client, RedisCodec.Utf8)
      } yield ZEnvironment(commands, pubSub)).toScopedZIO
    }

  private val config = ZLayer.succeed(Config(300, "localhost", 8888, uri"http://localhost:8080", "1.0.0"))

  def run: Task[Unit] =
    (for {
      counter <- ZIO.service[Messenger[CounterMessage]]
      _       <- counter.tell("c1")(IncrementCounter)
      _       <- counter.tell("c1")(DecrementCounter)
      _       <- counter.tell("c1")(IncrementCounter)
      _       <- counter.tell("c1")(IncrementCounter)
      _       <- counter.tell("c2")(IncrementCounter)
      _       <- counter.ask("c1")(GetCounter).debug
      _       <- counter.ask("c2")(GetCounter).debug
    } yield ()).provide(
      config,
      redis,
      Serialization.javaSerialization,
      StorageRedis.live,
      ShardManagerClient.local,
      Pods.noop,
      Sharding.live,
      CounterActor.live
    )
}
