package com.devsisters.shardcake

import com.devsisters.shardcake.interfaces.Serialization
import com.twitter.chill.{ KryoInstantiator, KryoPool, ScalaKryoInstantiator }
import zio.{ Has, Task, ZIO, ZLayer }

object KryoSerialization {

  /**
   * A layer that returns a serialization implementation using the Kryo library
   */
  val live: ZLayer[Any, Throwable, Has[Serialization]] =
    ZIO.effect {
      def kryoInstantiator: KryoInstantiator = new ScalaKryoInstantiator
      def poolSize: Int                      = 4 * java.lang.Runtime.getRuntime.availableProcessors
      KryoPool.withByteArrayOutputStream(poolSize, kryoInstantiator)
    }.map(kryoPool =>
      new Serialization {
        def encode(message: Any): Task[Array[Byte]] = ZIO.effect(kryoPool.toBytesWithClass(message))
        def decode[A](bytes: Array[Byte]): Task[A]  = ZIO.effect(kryoPool.fromBytes(bytes).asInstanceOf[A])
      }
    ).toLayer
}
