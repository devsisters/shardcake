package com.devsisters.sharding.interfaces

import com.devsisters.sharding.PodAddress
import com.devsisters.sharding.interfaces.ShardClient.BinaryMessage
import zio.Task

trait ShardClient {
  def sendMessage(message: BinaryMessage, pod: PodAddress): Task[Option[Array[Byte]]]
}

object ShardClient {
  case class BinaryMessage(entityId: String, entityType: String, body: Array[Byte])
}
