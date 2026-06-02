package com.devsisters.shardcake.protocol

import proteus._

object Sharding {
  given ProtobufDeriver = ProtobufDeriver

  case class AssignShardsRequest(shards: List[Int]) derives ProtobufCodec
  case class AssignShardsResponse() derives ProtobufCodec

  case class UnassignShardsRequest(shards: List[Int]) derives ProtobufCodec
  case class UnassignShardsResponse() derives ProtobufCodec

  case class SendRequest(entityId: String, entityType: String, body: Array[Byte], replyId: Option[String])
      derives ProtobufCodec
  case class SendResponse(body: Option[Array[Byte]]) derives ProtobufCodec

  case class PingShardsRequest() derives ProtobufCodec
  case class PingShardsResponse() derives ProtobufCodec

  val assignShards               = Rpc.unary[AssignShardsRequest, AssignShardsResponse]("AssignShards")
  val unassignShards             = Rpc.unary[UnassignShardsRequest, UnassignShardsResponse]("UnassignShards")
  val send                       = Rpc.unary[SendRequest, SendResponse]("Send")
  val sendStream                 = Rpc.clientStreaming[SendRequest, SendResponse]("SendStream")
  val sendAndReceiveStream       = Rpc.serverStreaming[SendRequest, SendResponse]("SendAndReceiveStream")
  val sendStreamAndReceiveStream = Rpc.bidiStreaming[SendRequest, SendResponse]("SendStreamAndReceiveStream")
  val pingShards                 = Rpc.unary[PingShardsRequest, PingShardsResponse]("PingShards")

  val service =
    Service("ShardingService")
      .rpc(assignShards)
      .rpc(unassignShards)
      .rpc(send)
      .rpc(sendStream)
      .rpc(sendAndReceiveStream)
      .rpc(sendStreamAndReceiveStream)
      .rpc(pingShards)
}

object GenerateProto {
  def main(args: Array[String]): Unit = {
    val outputDir = args.headOption.getOrElse {
      sys.error("Usage: GenerateProto <output-folder>")
    }
    Sharding.service.renderToFile(List.empty, outputDir, "sharding")
    println(s"Wrote $outputDir/sharding.proto")
  }
}
