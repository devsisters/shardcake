package com.devsisters.shardcake.interfaces

import zio.{ Has, Task, ULayer, ZIO, ZLayer, ZManaged }

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream }

/**
 * An interface to serialize user messages that will be sent between pods.
 */
trait Serialization {

  /**
   * Transforms the given message into binary
   */
  def encode(message: Any): Task[Array[Byte]]

  /**
   * Transform binary back into the given type
   */
  def decode[A](bytes: Array[Byte]): Task[A]
}

object Serialization {

  /**
   * A layer that uses Java serialization for encoding and decoding messages.
   * This is useful for testing and not recommended to use in production.
   */
  val javaSerialization: ULayer[Has[Serialization]] =
    ZLayer.succeed(new Serialization {
      def encode(message: Any): Task[Array[Byte]] = {
        val stream = new ByteArrayOutputStream()
        ZManaged
          .fromAutoCloseable(ZIO.effect(new ObjectOutputStream(stream)))
          .use(oos => ZIO.effect(oos.writeObject(message)).as(stream.toByteArray))
      }

      def decode[A](bytes: Array[Byte]): Task[A] =
        ZManaged
          .fromAutoCloseable(ZIO.effect(new ObjectInputStream(new ByteArrayInputStream(bytes))))
          .use(ois => ZIO.effect(ois.readObject.asInstanceOf[A]))
    })
}
