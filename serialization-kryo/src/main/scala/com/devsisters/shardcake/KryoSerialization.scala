package com.devsisters.shardcake

import com.devsisters.shardcake.interfaces.Serialization
import com.typesafe.config.ConfigFactory
import io.altoo.serialization.kryo.scala.ScalaKryoSerializer
import zio.{ Chunk, Task, ZIO }

object KryoSerialization {
  implicit def kryoSerialization[A](implicit serializer: ScalaKryoSerializer): Serialization[A] =
    new Serialization[A] {
      def encode(message: A): Task[Array[Byte]]                              = ZIO.fromTry(serializer.serialize(message))
      def decode(bytes: Array[Byte]): Task[A]                                = ZIO.fromTry(serializer.deserialize[A](bytes))
      override def encodeChunk(messages: Chunk[A]): Task[Chunk[Array[Byte]]] =
        ZIO.attempt(messages.map(serializer.serialize(_).get))
      override def decodeChunk(bytes: Chunk[Array[Byte]]): Task[Chunk[A]]    =
        ZIO.attempt(bytes.map(serializer.deserialize[A](_).get))
    }

  object Default {
    private lazy val defaultKryoSerializer: ScalaKryoSerializer =
      new ScalaKryoSerializer(ConfigFactory.defaultReference(), getClass.getClassLoader)

    implicit def defaultKryoSerialization[A]: Serialization[A] =
      kryoSerialization[A](defaultKryoSerializer)
  }
}
