package com.devsisters.shardcake

import zio.json._

case class Role(name: String)

object Role {
  implicit val encoder: JsonEncoder[Role] = DeriveJsonEncoder.gen[Role]
  implicit val decoder: JsonDecoder[Role] = DeriveJsonDecoder.gen[Role]
}
