package com.devsisters.shardcake.interfaces

import zio.{ Chunk, Task, ULayer, ZIO, ZLayer }

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

  /**
   * Transforms a chunk of messages into binary
   */
  def encodeChunk(messages: Chunk[Any]): Task[Chunk[Array[Byte]]] =
    ZIO.foreach(messages)(encode)

  /**
   * Transforms a chunk of binary back into the given type
   */
  def decodeChunk[A](bytes: Chunk[Array[Byte]]): Task[Chunk[A]] =
    ZIO.foreach(bytes)(decode[A])
}

object Serialization {

  /**
   * A layer that uses Java serialization for encoding and decoding messages.
   * This is useful for testing and not recommended to use in production.
   */
  val javaSerialization: ULayer[Serialization] =
    ZLayer.succeed(new Serialization {
      def encode(message: Any): Task[Array[Byte]] =
        ZIO.scoped {
          val stream = new ByteArrayOutputStream()
          ZIO
            .fromAutoCloseable(ZIO.attempt(new ObjectOutputStream(stream)))
            .flatMap(oos => ZIO.attempt(oos.writeObject(message)))
            .as(stream.toByteArray)
        }

      def decode[A](bytes: Array[Byte]): Task[A] =
        ZIO.scoped {
          ZIO
            .fromAutoCloseable(ZIO.attempt(new ObjectInputStream(new ByteArrayInputStream(bytes))))
            .flatMap(ois => ZIO.attempt(ois.readObject.asInstanceOf[A]))
        }
    })
}
