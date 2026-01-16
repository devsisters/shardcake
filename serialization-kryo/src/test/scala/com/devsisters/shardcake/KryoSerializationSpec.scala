package com.devsisters.shardcake

import zio.Scope
import zio.test._

object KryoSerializationSpec extends ZIOSpecDefault {
  def spec: Spec[TestEnvironment with Scope, Any] =
    suite("KryoSerializationSpec")(
      test("serialize back and forth") {
        case class Test(a: Int, b: String)
        val expected = Test(2, "test")
        for {
          bytes  <- KryoSerialization.Default.defaultKryoSerialization.encode(expected)
          actual <- KryoSerialization.Default.defaultKryoSerialization[Test].decode(bytes)
        } yield assertTrue(expected == actual)
      }
    )
}
