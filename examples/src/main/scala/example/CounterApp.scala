package example

import com.devsisters.shardcake._
import example.CounterActor.CounterMessage
import example.CounterActor.CounterMessage.{ DecrementCounter, GetCounter, IncrementCounter }
import sttp.client3.UriContext
import zio._

object CounterApp extends ZIOAppDefault {

  private val config     = ZLayer.succeed(Config(300, "localhost", 8888, uri"http://localhost:8080/api/graphql", "1.0.0"))
  private val grpcConfig = ZLayer.succeed(GrpcConfig(32 * 1024 * 1024))

  def run: Task[Unit] =
    ZIO.scoped {
      for {
        _       <- Sharding.registerScoped
        counter <- ZIO.service[Messenger[CounterMessage]]
        _       <- counter.sendDiscard("c1")(IncrementCounter)
        _       <- counter.sendDiscard("c1")(DecrementCounter)
        _       <- counter.sendDiscard("c1")(IncrementCounter)
        _       <- counter.sendDiscard("c1")(IncrementCounter)
        _       <- counter.sendDiscard("c2")(IncrementCounter)
        _       <- Clock.sleep(1 second)
        _       <- counter.send("c1")(GetCounter.apply).debug
        _       <- counter.send("c2")(GetCounter.apply).debug
      } yield ()
    }.provide(
      config,
      grpcConfig,
      redis,
      KryoSerialization.live,
      StorageRedis.live,
      ShardManagerClient.liveWithSttp,
      GrpcPods.live,
      Sharding.live,
      GrpcShardingService.live,
      CounterActor.live
    )
}
