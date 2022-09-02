package example.simple

import com.devsisters.shardcake._
import com.devsisters.shardcake.interfaces._
import zio._
import zio.clock.Clock
import zio.console.Console

object ShardManagerApp extends zio.App {

  val logging = Logging.debug
  val pods    = ZLayer.succeed(GrpcConfig.default) ++ logging >>> GrpcPods.live // use gRPC protocol
  val health  = pods >>> PodsHealth.local                                       // just ping a pod to see if it's alive
  val storage = Storage.memory                                                  // / store data in memory
  val layer   =
    ZLayer.succeed(ManagerConfig.default) ++ Clock.live ++ Console.live ++ logging ++ pods ++ health ++ storage >+>
      ShardManager.live // Shard Manager logic

  def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    Server.run.provideLayer(layer).exitCode
}
