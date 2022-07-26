package com.devsisters.shardcake

import com.devsisters.shardcake.interfaces.Serialization
import zio.{ Scope, ZIO }
import zio.test._

object JavaSerializationSpec extends ZIOSpecDefault {
  def spec: Spec[TestEnvironment with Scope, Any] =
    suite("JavaSerializationSpec")(
      test("serialize back and forth") {
        case class Test(a: Int, b: String)
        val expected = Test(2, "test")
        for {
          bytes  <- ZIO.serviceWithZIO[Serialization](_.encode(expected))
          actual <- ZIO.serviceWithZIO[Serialization](_.decode[Test](bytes))
        } yield assertTrue(expected == actual)
      }
    ).provideShared(Serialization.javaSerialization)
}
