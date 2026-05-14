package com.devsisters.shardcake

import com.devsisters.shardcake.interfaces.{ Pods, Storage }
import com.devsisters.shardcake.interfaces.Pods.BinaryMessage
import zio.{ Promise, Queue, RLayer, Task, ULayer, URLayer, ZIO, ZLayer }
import zio.stream.ZStream

object LocalSharding {

  trait LocalQueue {
    def localQueue: Queue[LocalQueueMessage]
  }

  sealed trait LocalQueueMessage
  object LocalQueueMessage {
    case class SendMessage(request: BinaryMessage, response: Promise[Nothing, Option[Array[Byte]]])
        extends LocalQueueMessage
    case class SendStream(
      request: ZStream[Any, Throwable, BinaryMessage],
      response: Promise[Nothing, Option[Array[Byte]]]
    ) extends LocalQueueMessage
    case class SendMessageAndReceiveStream(
      request: BinaryMessage,
      response: Promise[Nothing, ZStream[Any, Throwable, Array[Byte]]]
    ) extends LocalQueueMessage
    case class SendStreamAndReceiveStream(
      request: ZStream[Any, Throwable, BinaryMessage],
      response: Promise[Nothing, ZStream[Any, Throwable, Array[Byte]]]
    ) extends LocalQueueMessage
  }

  val localQueue: ULayer[LocalQueue] =
    ZLayer(
      Queue
        .unbounded[LocalQueueMessage]
        .map(queue =>
          new LocalQueue {
            def localQueue: Queue[LocalQueueMessage] = queue
          }
        )
    )

  val localPods: URLayer[LocalQueue, Pods] =
    ZLayer {
      ZIO.serviceWith[LocalQueue](_.localQueue).map { queue =>
        new Pods {
          def assignShards(pod: PodAddress, shards: Set[ShardId]): Task[Unit]   = ZIO.unit
          def unassignShards(pod: PodAddress, shards: Set[ShardId]): Task[Unit] = ZIO.unit
          def ping(pod: PodAddress): Task[Unit]                                 = ZIO.unit

          def sendMessage(pod: PodAddress, message: BinaryMessage): Task[Option[Array[Byte]]] =
            Promise.make[Nothing, Option[Array[Byte]]].flatMap { promise =>
              queue.offer(LocalQueueMessage.SendMessage(message, promise)) *> promise.await
            }

          def sendStream(
            pod: PodAddress,
            entityId: String,
            messages: ZStream[Any, Throwable, BinaryMessage]
          ): Task[Option[Array[Byte]]] =
            Promise.make[Nothing, Option[Array[Byte]]].flatMap { promise =>
              queue.offer(LocalQueueMessage.SendStream(messages, promise)).fork *> promise.await
            }

          def sendMessageAndReceiveStream(
            pod: PodAddress,
            message: BinaryMessage
          ): ZStream[Any, Throwable, Array[Byte]] =
            ZStream.unwrap {
              Promise.make[Nothing, ZStream[Any, Throwable, Array[Byte]]].flatMap { promise =>
                queue.offer(LocalQueueMessage.SendMessageAndReceiveStream(message, promise)) *> promise.await
              }
            }

          def sendStreamAndReceiveStream(
            pod: PodAddress,
            entityId: String,
            messages: ZStream[Any, Throwable, BinaryMessage]
          ): ZStream[Any, Throwable, Array[Byte]] =
            ZStream.unwrap {
              Promise.make[Nothing, ZStream[Any, Throwable, Array[Byte]]].flatMap { promise =>
                queue.offer(LocalQueueMessage.SendStreamAndReceiveStream(messages, promise)).fork *> promise.await
              }
            }
        }
      }
    }

  val localServer: RLayer[Sharding with LocalQueue, Unit] =
    ZLayer.scoped {
      for {
        sharding <- ZIO.service[Sharding]
        queue    <- ZIO.serviceWith[LocalQueue](_.localQueue)
        _        <- ZStream
                      .fromQueueWithShutdown(queue)
                      .runForeach {
                        case LocalQueueMessage.SendMessage(request, response)                 =>
                          sharding.sendToLocalEntity(request).flatMap(response.succeed)
                        case LocalQueueMessage.SendStream(request, response)                  =>
                          sharding.sendStreamToLocalEntity(request).flatMap(response.succeed)
                        case LocalQueueMessage.SendMessageAndReceiveStream(request, response) =>
                          response.succeed(sharding.sendToLocalEntityAndReceiveStream(request))
                        case LocalQueueMessage.SendStreamAndReceiveStream(request, response)  =>
                          response.succeed(sharding.sendStreamToLocalEntityAndReceiveStream(request))
                      }
                      .forkScoped
      } yield ()
    }

  /**
   * A special layer meant for testing that uses a local queue rather than an external transport.
   * This layer will only work in a single JVM and is not suitable for production use.
   */
  val live: RLayer[ShardManagerClient with Storage with Config, Sharding] =
    ZLayer.makeSome[ShardManagerClient with Storage with Config, Sharding](
      localQueue,
      localPods,
      localServer,
      Sharding.live
    )
}
