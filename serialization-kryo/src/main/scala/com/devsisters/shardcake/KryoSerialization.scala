package com.devsisters.shardcake

import com.devsisters.shardcake.interfaces.Serialization
import com.typesafe.config.{ Config, ConfigFactory }
import io.altoo.serialization.kryo.scala.ScalaKryoSerializer
import zio.{ Task, ZIO, ZLayer }

object KryoSerialization {

  /**
   * A layer that returns a serialization implementation using the Kryo library.
   */
  val live: ZLayer[Any, Throwable, Serialization] =
    ZLayer(ZIO.attempt(ConfigFactory.defaultReference()).flatMap(make))

  /**
   * A layer that returns a serialization implementation using the Kryo library, taking a Config object.
   * See https://github.com/altoo-ag/scala-kryo-serialization for more details about configuration.
   */
  def liveWithConfig(config: Config): ZLayer[Any, Throwable, Serialization] =
    ZLayer(make(config))

  private def make(config: Config): Task[Serialization] =
    ZIO.attempt {
      new ScalaKryoSerializer(config, getClass.getClassLoader)
    }.map(serializer =>
      new Serialization {
        def encode(message: Any): Task[Array[Byte]] = ZIO.fromTry(serializer.serialize(message))
        def decode[A](bytes: Array[Byte]): Task[A]  = ZIO.fromTry(serializer.deserialize[A](bytes))
      }
    )
}
