package com.devsisters.shardcake.kryo

import com.devsisters.shardcake.interfaces.MessageCodec
import com.typesafe.config.{ Config, ConfigFactory }
import io.altoo.serialization.kryo.scala.ScalaKryoSerializer

/**
 * A universal `MessageCodec[Msg]` backed by Kryo via altoo's `ScalaKryoSerializer`.
 *
 * Because Kryo is reflective and uniform across types, one generic codec serves every
 * message type — there is no per-type derivation.
 * Users typically import the package-level given via `import com.devsisters.shardcake.kryo.given`
 * and define their `EntityType` / `TopicType` without further ceremony.
 *
 * To use a custom Kryo configuration, shadow the default `given` with
 * `given [Msg]: MessageCodec[Msg] = KryoMessageCodec.fromConfig[Msg](myConfig)`.
 */
final class KryoMessageCodec[Msg] private[kryo] (serializer: ScalaKryoSerializer) extends MessageCodec[Msg] {
  private val universalEncoder: Any => Array[Byte] =
    (value: Any) => serializer.serialize(value).get

  def encodeMessage(message: Msg): Array[Byte] =
    serializer.serialize(message).get

  def decodeMessage(bytes: Array[Byte]): Msg =
    serializer.deserialize[Any](bytes).get.asInstanceOf[Msg]

  def replyEncoder(decoded: Msg): Any => Array[Byte] =
    universalEncoder

  def replyDecoder[Res](sample: Msg): Array[Byte] => Res =
    bytes => serializer.deserialize[Any](bytes).get.asInstanceOf[Res]

  def streamReplyDecoder[Res](sample: Msg): Array[Byte] => Res =
    bytes => serializer.deserialize[Any](bytes).get.asInstanceOf[Res]
}

object KryoMessageCodec {

  /**
   * Shared `ScalaKryoSerializer` built from Typesafe Config's reference configuration.
   * The serializer manages a Kryo pool internally and is safe to share across the whole pod.
   */
  lazy val defaultSerializer: ScalaKryoSerializer =
    new ScalaKryoSerializer(ConfigFactory.defaultReference(), getClass.getClassLoader)

  /**
   * A `KryoMessageCodec[Msg]` backed by [[defaultSerializer]].
   */
  def default[Msg]: KryoMessageCodec[Msg] = new KryoMessageCodec[Msg](defaultSerializer)

  /**
   * A `KryoMessageCodec[Msg]` backed by a fresh `ScalaKryoSerializer` built from the
   * given Typesafe Config. See https://github.com/altoo-ag/scala-kryo-serialization for
   * the available options.
   */
  def fromConfig[Msg](config: Config): KryoMessageCodec[Msg] =
    new KryoMessageCodec[Msg](new ScalaKryoSerializer(config, getClass.getClassLoader))
}
