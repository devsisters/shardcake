package com.devsisters.shardcake

import com.devsisters.shardcake.interfaces.Storage
import zio.{ Config => _, _ }
import zio.stream.ZStream

import java.util.concurrent.ForkJoinPool

object Server {
  sealed trait Message

  object Message {
    case class Ping(msg: String, replier: Replier[String]) extends Message
  }

  object PingPongEntity extends EntityType[Message]("ping-pong")

  private def behavior(entityId: String, messages: Dequeue[Message]): RIO[Sharding, Nothing] =
    messages.take.flatMap { case Message.Ping(msg, replier) => replier.reply(msg) }.forever

  private val shardManagerClient: ZLayer[Config, Nothing, ShardManagerClient] =
    ZLayer {
      for {
        config <- ZIO.service[Config]
        pod     = PodAddress("localhost", config.shardingPort)
        shards  = (1 to config.numberOfShards).map(_ -> Some(pod)).toMap
      } yield new ShardManagerClient {
        def register(podAddress: PodAddress, role: Role): Task[Unit]       = ZIO.unit
        def unregister(podAddress: PodAddress, role: Role): Task[Unit]     = ZIO.unit
        def notifyUnhealthyPod(podAddress: PodAddress): Task[Unit]         = ZIO.unit
        def getAssignments(role: Role): Task[Map[Int, Option[PodAddress]]] = ZIO.succeed(shards)
      }
    }

  private val memory: ULayer[Storage] =
    ZLayer {
      for {
        assignmentsRef <- Ref.make(Map.empty[Role, Map[ShardId, Option[PodAddress]]])
        podsRef        <- Ref.make(Map.empty[PodAddress, Pod])
      } yield new Storage {
        def getAssignments(role: Role): Task[Map[ShardId, Option[PodAddress]]]                       =
          assignmentsRef.get.map(_.getOrElse(role, Map.empty))
        def saveAssignments(role: Role, assignments: Map[ShardId, Option[PodAddress]]): Task[Unit]   =
          assignmentsRef.update(_.updated(role, assignments))
        def assignmentsStream(role: Role): ZStream[Any, Throwable, Map[ShardId, Option[PodAddress]]] = ZStream.never
        def getPods: Task[Map[PodAddress, Pod]]                                                      = podsRef.get
        def savePods(pods: Map[PodAddress, Pod]): Task[Unit]                                         = podsRef.set(pods)
      }
    }

  private val grpcConfig: ULayer[GrpcConfig] =
    ZLayer.succeed(GrpcConfig.default.copy(executor = Some(ForkJoinPool.commonPool())))

  val sharding: ZLayer[Config, Throwable, Sharding with GrpcConfig] =
    ZLayer.makeSome[Config, Sharding with GrpcConfig](
      KryoSerialization.live,
      memory,
      grpcConfig,
      shardManagerClient,
      GrpcPods.live,
      Sharding.live
    )

  val run: Task[Unit] =
    ZIO
      .scoped[Sharding](
        for {
          _ <- Sharding.registerEntity(PingPongEntity, behavior)
          _ <- Sharding.registerScoped
          _ <- ZIO.never
        } yield ()
      )
      .provide(
        ZLayer.succeed(Config.default),
        sharding,
        GrpcShardingService.live
      )
}
