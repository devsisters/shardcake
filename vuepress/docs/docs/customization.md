# Customization

As demonstrated in the [Getting Started](README.md#key-components), there are several parts of the system that are entirely customizable.
For each of them, Shardcake provides at least a fake implementation for testing and a prod-ready implementation using common technologies. 

Feel free to implement your own!

![architecture diagram](/shardcake/arch.png)

## Storage

The `Storage` trait defines how to store and access pods and shards assignments.
It contains 5 methods: `getAssignments`/`saveAssignments` to store assignments, `getPods`/`savePods` to store pods, and `assignmentsStream` to receive assignment updates.

```scala
trait Storage {
  def getAssignments: Task[Map[ShardId, Option[PodAddress]]]
  def saveAssignments(assignments: Map[ShardId, Option[PodAddress]]): Task[Unit]
  
  def assignmentsStream: ZStream[Any, Throwable, Map[Int, Option[PodAddress]]]
  
  def getPods: Task[Map[PodAddress, Pod]]
  def savePods(pods: Map[PodAddress, Pod]): Task[Unit]
}
```

For testing, you can use the `Storage.memory` layer that keeps data in memory.

Shardcake provides an implementation of `Storage` using Redis. To use it, add the following dependency:
```scala
libraryDependencies += "com.devsisters" %% "shardcake-storage-redis" % "2.1.0"
```
You can then simply use the `StorageRedis.live` layer.

It requires a `RedisConfig` with the following options:
- `assignmentsKey`: the key to store shard assignments in Redis
- `podsKey`: the key to store registered pods in Redis

It also requires a `Redis` object, which is an alias to `RedisCommands[Task, String, String] with PubSubCommands[fs2Stream, String, String]` from the [redis4cats](https://redis4cats.profunktor.dev/) library used under the hood.
Here's an example how to build it:

```scala
import com.devsisters.shardcake.StorageRedis.Redis
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.pubsub.PubSub
import zio.interop.catz._
import zio.{ Task, ZEnvironment, ZIO, ZLayer }

val redis: ZLayer[Any, Throwable, Redis] =
  ZLayer.scopedEnvironment {
    implicit val runtime: zio.Runtime[Any] = zio.Runtime.default
    implicit val logger: Log[Task]         = new Log[Task] {
      override def debug(msg: => String): Task[Unit] = ZIO.unit
      override def error(msg: => String): Task[Unit] = ZIO.logError(msg)
      override def info(msg: => String): Task[Unit]  = ZIO.logDebug(msg)
    }

    (for {
      client   <- RedisClient[Task].from("redis://foobared@localhost")
      commands <- Redis[Task].fromClient(client, RedisCodec.Utf8)
      pubSub   <- PubSub.mkPubSubConnection[Task, String, String](client, RedisCodec.Utf8)
    } yield ZEnvironment(commands, pubSub)).toScopedZIO
  }
```

## Messaging Protocol

The `Pods` trait defines how to communicate with remote pods.
It is used both by the Shard Manager for assigning and unassigning shards, and by pods for internal communication (forward messages to each other).

```scala
trait Pods {
  def assignShards(pod: PodAddress, shards: Set[ShardId]): Task[Unit]
  def unassignShards(pod: PodAddress, shards: Set[ShardId]): Task[Unit]
  def ping(pod: PodAddress): Task[Unit]
  
  def sendMessage(pod: PodAddress, message: BinaryMessage): Task[Option[Array[Byte]]]
  def sendMessageStreaming(pod: PodAddress, message: BinaryMessage): ZStream[Any, Throwable, Array[Byte]]
}
```
For testing, you can use the `Pods.noop` layer that does nothing.

Shardcake provides an implementation of `Pods` using the gRPC protocol. To use it, add the following dependency:
```scala
libraryDependencies += "com.devsisters" %% "shardcake-protocol-grpc" % "2.1.0"
```
You can then simply use the `GrpcPods.live` layer.

On pods, you also need expose the gRPC API. This is done by adding the `GrpcShardingService.live` layer to your environment. You don't need this one on the Shard Manager.

## Serialization

The `Serialization` trait defines how to serialize user messages that will be sent between pods.
It contains 2 methods `encode` and `decode` that define how to transform a give type from and to bytes.

```scala
trait Serialization {
  def encode(message: Any): Task[Array[Byte]]
  def decode[A](bytes: Array[Byte]): Task[A]
}
```
For testing, you can use the `Serialization.javaSerialization` layer that uses Java Serialization (not recommended in production).

Shardcake provides an implementation of `Serialization` using the [Kryo](https://github.com/EsotericSoftware/kryo) binary serialization library. To use it, add the following dependency:
```scala
libraryDependencies += "com.devsisters" %% "shardcake-serialization-kryo" % "2.1.0"
```
You can then simply use the `KryoSerialization.live` layer.

::: tip Server updates and message versioning
- Messages are not persisted, which means that if you stop and restart the whole system, you can change anything in the messages format.
- On the other hand, if you wish to do rolling updates (update servers progressively without downtime), you need to be careful with changes in the messages format.
- What you can do largely depends on your serialization mechanism, some solutions allow changes while some others are very restrictive.
  [Kryo](https://github.com/EsotericSoftware/kryo) by default is pretty strict and won't support most changes, but there are settings to support more (at the cost of some performance or message size).
- When you can't modify existing messages, an option is to create new messages that won't be used until the rolling update is finished (so you won't have cases where old nodes receive new messages).
:::

## Health

The `PodsHealth` trait defines how to know if a pod is still alive or is dead (in which case, we should reassign all its shards).

```scala
trait PodsHealth {
  def isAlive(podAddress: PodAddress): UIO[Boolean]
}
```
For testing, you can use the `PodsHealth.noop` layer that always returns true, or the `PodsHealth.local` layer that uses `ping` from the [Messaging Protocol](#messaging-protocol) to check if a pod is alive.

Shardcake provides an implementation of `PodsHealth` using the [Kubernetes](https://kubernetes.io) API. To use it, add the following dependency:
```scala
libraryDependencies += "com.devsisters" %% "shardcake-health-k8s" % "2.1.0"
```
You can then simply use the `K8sPodsHealth.live` layer. This is requiring a `Pods` layer that comes from [zio-k8s](https://coralogix.github.io/zio-k8s/docs/overview/overview_gettingstarted).

::: tip Examples
Check the [examples](https://github.com/devsisters/shardcake/tree/series/2.x/examples/src/main/scala/example/complex) folder that contains a full example using Redis, gRPC and Kryo seralization.
:::