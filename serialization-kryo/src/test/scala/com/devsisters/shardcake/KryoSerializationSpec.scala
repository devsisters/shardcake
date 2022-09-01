package com.devsisters.shardcake

import com.devsisters.shardcake.interfaces.Serialization
import zio.ZIO
import zio.test._
import zio.test.environment.TestEnvironment

object KryoSerializationSpec extends DefaultRunnableSpec {
  def spec: ZSpec[TestEnvironment, Any] =
    suite("KryoSerializationSpec")(
      testM("serialize back and forth") {
        case class Test(a: Int, b: String)
        val expected = Test(2, "test")
        for {
          bytes  <- ZIO.serviceWith[Serialization](_.encode(expected))
          actual <- ZIO.serviceWith[Serialization](_.decode[Test](bytes))
        } yield assertTrue(expected == actual)
      }
    ).provideLayerShared(KryoSerialization.live.mapError(TestFailure.fail))
}
