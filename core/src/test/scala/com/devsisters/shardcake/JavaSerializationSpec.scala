package com.devsisters.shardcake

import com.devsisters.shardcake.interfaces.JavaSerialization
import zio.Scope
import zio.test._

object JavaSerializationSpec extends ZIOSpecDefault {
  def spec: Spec[TestEnvironment with Scope, Any] =
    suite("JavaSerializationSpec")(
      test("serialize back and forth") {
        case class Test(a: Int, b: String)
        val expected = Test(2, "test")
        for {
          bytes  <- JavaSerialization.javaSerialization.encode(expected)
          actual <- JavaSerialization.javaSerialization[Test].decode(bytes)
        } yield assertTrue(expected == actual)
      }
    )
}
