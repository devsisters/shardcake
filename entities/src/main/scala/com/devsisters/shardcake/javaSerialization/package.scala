package com.devsisters.shardcake

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream }

import com.devsisters.shardcake.interfaces.MessageCodec

/**
 * Java-serialization backend given.
 *
 * Import with `import com.devsisters.shardcake.javaSerialization.given` to enable
 * Java-serialization-backed transport for all `EntityType` / `TopicType` declarations
 * in the surrounding scope.
 *
 * Mostly useful for tests and examples. For production deployments prefer
 * `com.devsisters.shardcake.kryo` or a macro-derived backend.
 */
package object javaSerialization {
  given derive[Msg]: MessageCodec[Msg] = new MessageCodec[Msg] {
    private val universalEncoder: Any => Array[Byte] = (v: Any) => writeBytes(v)

    def encodeMessage(message: Msg): Array[Byte] = writeBytes(message)

    def decodeMessage(bytes: Array[Byte]): Msg = readBytes(bytes)

    def replyEncoder(decoded: Msg): Any => Array[Byte] = universalEncoder

    def replyDecoder[Res](sample: Msg): Array[Byte] => Res       = bytes => readBytes[Res](bytes)
    def streamReplyDecoder[Res](sample: Msg): Array[Byte] => Res = bytes => readBytes[Res](bytes)

    private def writeBytes(value: Any): Array[Byte] = {
      val baos = new ByteArrayOutputStream
      val oos  = new ObjectOutputStream(baos)
      try oos.writeObject(value)
      finally oos.close()
      baos.toByteArray
    }

    private def readBytes[A](bytes: Array[Byte]): A = {
      val ois = new ObjectInputStream(new ByteArrayInputStream(bytes))
      try ois.readObject().asInstanceOf[A]
      finally ois.close()
    }
  }
}
