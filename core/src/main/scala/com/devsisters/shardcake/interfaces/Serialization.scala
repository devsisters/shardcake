package com.devsisters.shardcake.interfaces

import zio.{ Chunk, Task, ZIO }

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream }

/**
 * An interface to serialize user messages that will be sent between pods.
 */
trait Serialization[Msg] {

  /**
   * Transforms the given message into binary
   */
  def encode(message: Msg): Task[Array[Byte]]

  /**
   * Transform binary back into the given type
   */
  def decode(bytes: Array[Byte]): Task[Msg]

  /**
   * Transforms a chunk of messages into binary
   */
  def encodeChunk(messages: Chunk[Msg]): Task[Chunk[Array[Byte]]] =
    ZIO.foreach(messages)(encode)

  /**
   * Transforms a chunk of binary back into the given type
   */
  def decodeChunk(bytes: Chunk[Array[Byte]]): Task[Chunk[Msg]] =
    ZIO.foreach(bytes)(decode)
}

object Serialization {
  implicit val unitSerialization: Serialization[Unit] = new Serialization[Unit] {
    def encode(message: Unit): Task[Array[Byte]] = ZIO.succeed(Array.emptyByteArray)
    def decode(bytes: Array[Byte]): Task[Unit]   = ZIO.unit
  }
}

object JavaSerialization {

  /**
   * A Java serialization for encoding and decoding messages.
   * This is useful for testing and not recommended to use in production.
   */
  implicit def javaSerialization[T]: Serialization[T] =
    new Serialization[T] {
      def encode(message: T): Task[Array[Byte]] =
        ZIO.scoped {
          val stream = new ByteArrayOutputStream()
          ZIO
            .fromAutoCloseable(ZIO.attempt(new ObjectOutputStream(stream)))
            .flatMap(oos => ZIO.attempt(oos.writeObject(message)))
            .as(stream.toByteArray)
        }

      def decode(bytes: Array[Byte]): Task[T] =
        ZIO.scoped {
          ZIO
            .fromAutoCloseable(ZIO.attempt(new ObjectInputStream(new ByteArrayInputStream(bytes))))
            .flatMap(ois => ZIO.attempt(ois.readObject.asInstanceOf[T]))
        }
    }
}
