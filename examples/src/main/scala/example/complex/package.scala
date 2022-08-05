package example

import com.devsisters.shardcake.StorageRedis.fs2Stream
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.pubsub.{ PubSub, PubSubCommands }
import dev.profunktor.redis4cats.{ Redis, RedisCommands }
import zio.interop.catz._
import zio.{ Task, ZEnvironment, ZIO, ZLayer }

package object complex {
  val redis
    : ZLayer[Any, Throwable, RedisCommands[Task, String, String] with PubSubCommands[fs2Stream, String, String]] =
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
}
