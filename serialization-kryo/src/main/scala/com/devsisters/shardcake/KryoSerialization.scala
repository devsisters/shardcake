package com.devsisters.shardcake

import com.devsisters.shardcake.interfaces.Serialization
import com.typesafe.config.{ Config, ConfigFactory }
import io.altoo.serialization.kryo.scala.ScalaKryoSerializer
import zio.{ Has, Task, ZIO, ZLayer }

object KryoSerialization {

  /**
   * A layer that returns a serialization implementation using the Kryo library.
   */
  val live: ZLayer[Any, Throwable, Has[Serialization]] =
    ZIO.effect(ConfigFactory.defaultReference()).flatMap(make).toLayer

  /**
   * A layer that returns a serialization implementation using the Kryo library, taking a Config object.
   * See https://github.com/altoo-ag/scala-kryo-serialization for more details about configuration.
   */
  def liveWithConfig(config: Config): ZLayer[Any, Throwable, Has[Serialization]] =
    make(config).toLayer

  private def make(config: Config): Task[Serialization] =
    ZIO.effect {
      new ScalaKryoSerializer(config, getClass.getClassLoader)
    }.map(serializer =>
      new Serialization {
        def encode(message: Any): Task[Array[Byte]] = ZIO.fromTry(serializer.serialize(message.asInstanceOf[AnyRef]))
        def decode[A](bytes: Array[Byte]): Task[A]  = ZIO.fromTry(serializer.deserialize[A](bytes))
      }
    )
}
