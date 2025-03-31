package com.devsisters.shardcake

import zio.json._

case class Pod(address: PodAddress, version: String, role: Role)

object Pod {
  implicit val encoder: JsonEncoder[Pod] = DeriveJsonEncoder.gen[Pod]
  implicit val decoder: JsonDecoder[Pod] = DeriveJsonDecoder.gen[Pod]
}
