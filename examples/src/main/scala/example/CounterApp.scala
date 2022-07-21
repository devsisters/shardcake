package example

import com.devsisters.sharding._
import example.CounterActor.CounterMessage
import example.CounterActor.CounterMessage.{ DecrementCounter, GetCounter, IncrementCounter }
import sttp.client3.UriContext
import zio._

object CounterApp extends ZIOAppDefault {

  private val config     = ZLayer.succeed(Config(300, "localhost", 8888, uri"http://localhost:8080/api/graphql", "1.0.0"))
  private val grpcConfig = ZLayer.succeed(GrpcConfig(32 * 1024 * 1024))

  def run: Task[Unit] =
    (for {
      _       <- ZIO.serviceWithZIO[Sharding](_.register)
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
      grpcConfig,
      redis,
      KryoSerialization.live,
      StorageRedis.live,
      ShardManagerClient.sttpLive,
      GrpcPods.live,
      Sharding.live,
      GrpcShardingService.live,
      CounterActor.live
    )
}
