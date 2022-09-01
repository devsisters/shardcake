package example

import com.devsisters.shardcake.StorageRedis.Redis
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.pubsub.PubSub
import zio.blocking.Blocking
import zio.clock.Clock
import zio.interop.catz._
import zio.{ Has, Task, ZIO, ZLayer }

package object complex {
  val redis: ZLayer[Any, Throwable, Redis] = {
    implicit val runtime: zio.Runtime[Clock with Blocking] = zio.Runtime.default
    implicit val logger: Log[Task]                         = new Log[Task] {
      override def debug(msg: => String): Task[Unit] = ZIO.debug(msg)
      override def error(msg: => String): Task[Unit] = ZIO.debug(msg)
      override def info(msg: => String): Task[Unit]  = ZIO.debug(msg)
    }

    (for {
      client   <- RedisClient[Task].from("redis://foobared@localhost")
      commands <- Redis[Task].fromClient(client, RedisCodec.Utf8)
      pubSub   <- PubSub.mkPubSubConnection[Task, String, String](client, RedisCodec.Utf8)
    } yield Has.allOf(commands, pubSub)).toManagedZIO.toLayerMany
  }
}
