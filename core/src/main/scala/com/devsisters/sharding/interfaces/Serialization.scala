package com.devsisters.sharding.interfaces

import zio.{ Task, ULayer, ZIO, ZLayer }

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream }

trait Serialization {
  def encode(body: Any): Task[Array[Byte]]
  def decode[A](bytes: Array[Byte]): Task[A]
}

object Serialization {
  val javaSerialization: ULayer[Serialization] =
    ZLayer.succeed(new Serialization {
      def encode(body: Any): Task[Array[Byte]] =
        ZIO.scoped {
          val stream = new ByteArrayOutputStream()
          ZIO
            .fromAutoCloseable(ZIO.attempt(new ObjectOutputStream(stream)))
            .flatMap(oos => ZIO.attempt(oos.writeObject(body)))
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
