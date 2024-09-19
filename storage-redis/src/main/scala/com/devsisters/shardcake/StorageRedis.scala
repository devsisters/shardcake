package com.devsisters.shardcake

import com.devsisters.shardcake.interfaces.Storage
import dev.profunktor.redis4cats.RedisCommands
import dev.profunktor.redis4cats.data.RedisChannel
import dev.profunktor.redis4cats.pubsub.PubSubCommands
import zio.stream.ZStream
import zio.stream.interop.fs2z._
import zio.{ Task, ZIO, ZLayer }

import scala.collection.compat._

object StorageRedis {
  type fs2Stream[A] = fs2.Stream[Task, A]
  type Redis        = RedisCommands[Task, String, String] with PubSubCommands[fs2Stream, String, String]

  /**
   * A layer that returns a Storage implementation using Redis
   */
  val live: ZLayer[Redis with RedisConfig, Nothing, Storage] =
    ZLayer {
      for {
        config       <- ZIO.service[RedisConfig]
        stringClient <- ZIO.service[RedisCommands[Task, String, String]]
        pubSubClient <- ZIO.service[PubSubCommands[fs2Stream, String, String]]
      } yield new Storage {
        def getAssignments: Task[Map[ShardId, Option[PodAddress]]] =
          stringClient
            .hGetAll(config.assignmentsKey)
            .map(_.flatMap { case (k, v) =>
              val pod = if (v.isEmpty) None else PodAddress(v)
              k.toIntOption.map(_ -> pod)
            })

        def saveAssignments(assignments: Map[ShardId, Option[PodAddress]]): Task[Unit] =
          stringClient.hSet(
            config.assignmentsKey,
            assignments.map { case (k, v) => k.toString -> v.fold("")(_.toString) }
          ) *>
            pubSubClient
              .publish(RedisChannel(config.assignmentsKey))(fs2.Stream.eval[Task, String](ZIO.succeed("ping")))
              .toZStream(1)
              .runDrain

        def assignmentsStream: ZStream[Any, Throwable, Map[ShardId, Option[PodAddress]]] =
          pubSubClient.subscribe(RedisChannel(config.assignmentsKey)).toZStream(1).mapZIO(_ => getAssignments)

        def getPods: Task[Map[PodAddress, Pod]] =
          stringClient
            .hGetAll(config.podsKey)
            .map(_.toList.flatMap { case (k, v) => PodAddress(k).map(address => address -> Pod(address, v)) }.toMap)

        def savePods(pods: Map[PodAddress, Pod]): Task[Unit] =
          stringClient.del(config.podsKey) *>
            stringClient
              .hSet(config.podsKey, pods.map { case (k, v) => k.toString -> v.version })
              .when(pods.nonEmpty)
              .unit
      }
    }
}
