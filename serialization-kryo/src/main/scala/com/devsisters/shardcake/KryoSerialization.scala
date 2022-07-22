package com.devsisters.shardcake

import com.devsisters.shardcake.interfaces.Serialization
import com.twitter.chill.{ KryoInstantiator, KryoPool, ScalaKryoInstantiator }
import zio.{ Task, ZIO, ZLayer }

object KryoSerialization {
  val live: ZLayer[Any, Throwable, Serialization] =
    ZLayer {
      ZIO.attempt {
        def kryoInstantiator: KryoInstantiator = new ScalaKryoInstantiator
        def poolSize: Int                      = 4 * java.lang.Runtime.getRuntime.availableProcessors
        KryoPool.withByteArrayOutputStream(poolSize, kryoInstantiator)
      }.map(kryoPool =>
        new Serialization {
          def encode(body: Any): Task[Array[Byte]]   = ZIO.attempt(kryoPool.toBytesWithClass(body))
          def decode[A](bytes: Array[Byte]): Task[A] = ZIO.attempt(kryoPool.fromBytes(bytes).asInstanceOf[A])
        }
      )
    }
}
