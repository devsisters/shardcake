package example.simple

import com.devsisters.shardcake._
import com.devsisters.shardcake.interfaces._
import zio._
import zio.clock.Clock
import zio.console.Console

object ShardManagerApp extends zio.App {

  val clock   = Clock.live
  val console = Console.live
  val logging = console >>> Logging.console
  val pods    = ZLayer.succeed(GrpcConfig.default) ++ logging >>> GrpcPods.live // use gRPC protocol
  val health  = pods >>> PodsHealth.local                                       // just ping a pod to see if it's alive
  val storage = Storage.memory                                                  // / store data in memory
  val layer   =
    ZLayer.succeed(ManagerConfig.default) ++ clock ++ console ++ logging ++ pods ++ health ++ storage >+>
      ShardManager.live // Shard Manager logic

  def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    Server.run.provideLayer(layer).exitCode
}
