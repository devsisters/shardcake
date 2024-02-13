package com.devsisters.shardcake

import scala.jdk.CollectionConverters._

import com.devsisters.shardcake.interfaces.Storage
import org.redisson.api.RedissonClient
import org.redisson.api.listener.MessageListener
import org.redisson.client.codec.StringCodec
import zio.stream.ZStream
import zio.{ Queue, Task, Unsafe, ZIO, ZLayer }

object StorageRedis {

  /**
   * A layer that returns a Storage implementation using Redis
   */
  val live: ZLayer[RedissonClient with RedisConfig, Nothing, Storage] =
    ZLayer {
      for {
        config          <- ZIO.service[RedisConfig]
        redisClient     <- ZIO.service[RedissonClient]
        assignmentsMap   = redisClient.getMap[String, String](config.assignmentsKey)
        podsMap          = redisClient.getMap[String, String](config.podsKey)
        assignmentsTopic = redisClient.getTopic(config.assignmentsKey, StringCodec.INSTANCE)
      } yield new Storage {
        def getAssignments: Task[Map[ShardId, Option[PodAddress]]]                       =
          ZIO
            .fromCompletionStage(assignmentsMap.readAllEntrySetAsync())
            .map(
              _.asScala
                .flatMap(entry =>
                  entry.getKey.toIntOption.map(
                    _ -> (if (entry.getValue.isEmpty) None
                          else PodAddress(entry.getValue))
                  )
                )
                .toMap
            )
        def saveAssignments(assignments: Map[ShardId, Option[PodAddress]]): Task[Unit]   =
          ZIO.fromCompletionStage(assignmentsMap.putAllAsync(assignments.map { case (k, v) =>
            k.toString -> v.fold("")(_.toString)
          }.asJava)) *>
            ZIO.fromCompletionStage(assignmentsTopic.publishAsync("ping")).unit
        def assignmentsStream: ZStream[Any, Throwable, Map[ShardId, Option[PodAddress]]] =
          ZStream.unwrap {
            for {
              queue   <- Queue.unbounded[String]
              runtime <- ZIO.runtime[Any]
              _       <- ZIO.fromCompletionStage(
                           assignmentsTopic.addListenerAsync(
                             classOf[String],
                             new MessageListener[String] {
                               def onMessage(channel: CharSequence, msg: String): Unit =
                                 Unsafe.unsafe(implicit unsafe => runtime.unsafe.run(queue.offer(msg)))
                             }
                           )
                         )
            } yield ZStream.fromQueueWithShutdown(queue).mapZIO(_ => getAssignments)
          }
        def getPods: Task[Map[PodAddress, Pod]]                                          =
          ZIO
            .fromCompletionStage(podsMap.readAllEntrySetAsync())
            .map(
              _.asScala
                .flatMap(entry => PodAddress(entry.getKey).map(address => address -> Pod(address, entry.getValue)))
                .toMap
            )
        def savePods(pods: Map[PodAddress, Pod]): Task[Unit]                             =
          ZIO.fromCompletionStage(podsMap.putAllAsync(pods.map { case (k, v) => k.toString -> v.version }.asJava)).unit
      }
    }
}
