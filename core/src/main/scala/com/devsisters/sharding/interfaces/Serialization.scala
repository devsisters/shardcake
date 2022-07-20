package com.devsisters.sharding.interfaces

import zio.Task

trait Serialization {
  def encode(body: Any): Task[Array[Byte]]
  def decode(bytes: Array[Byte]): Task[Any]
}
