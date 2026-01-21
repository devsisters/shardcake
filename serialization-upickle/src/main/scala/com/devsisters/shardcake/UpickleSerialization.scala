package com.devsisters.shardcake

import com.devsisters.shardcake.interfaces.Serialization
import upickle.default._
import zio.{ Chunk, Task, ZIO }

import scala.util.Try

object UpickleSerialization {
  implicit def upickleSerialization[A](implicit rw: ReadWriter[A]): Serialization[A] =
    new Serialization[A] {
      def encode(message: A): Task[Array[Byte]]                              = ZIO.fromTry(Try(writeBinary(message)))
      def decode(bytes: Array[Byte]): Task[A]                                = ZIO.fromTry(Try(readBinary[A](bytes)))
      override def encodeChunk(messages: Chunk[A]): Task[Chunk[Array[Byte]]] =
        ZIO.attempt(messages.map(m => Try(writeBinary(m)).get))
      override def decodeChunk(bytes: Chunk[Array[Byte]]): Task[Chunk[A]]    =
        ZIO.attempt(bytes.map(m => Try(readBinary[A](m)).get))
    }
}
