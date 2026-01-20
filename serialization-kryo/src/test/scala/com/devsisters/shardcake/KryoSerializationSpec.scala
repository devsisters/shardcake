package com.devsisters.shardcake

import zio.Scope
import zio.test._

object KryoSerializationSpec extends ZIOSpecDefault {
  def spec: Spec[TestEnvironment with Scope, Any] =
    suite("KryoSerializationSpec")(
      test("serialize back and forth") {
        case class Test(a: Int, b: String)
        val expected      = Test(2, "test")
        val serialization = KryoSerialization.Default.defaultKryoSerialization[Test]
        for {
          bytes  <- serialization.encode(expected)
          actual <- serialization.decode(bytes)
        } yield assertTrue(expected == actual)
      }
    )
}
