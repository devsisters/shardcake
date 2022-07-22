package com.devsisters.shardcake.errors

import com.devsisters.shardcake.EntityType

case class AskTimeoutException[A](entityType: EntityType[A], entityId: String, body: A) extends Exception {
  override def getMessage: String =
    s"Timeout sending message to ${entityType.value} $entityId - $body"
}
