package com.devsisters.shardcake

/**
 * The configuration for the gRPC client.
 * @param maxInboundMessageSize the maximum message size allowed to be received by the grpc client
 */
case class GrpcConfig(maxInboundMessageSize: Int)

object GrpcConfig {
  val default: GrpcConfig =
    GrpcConfig(maxInboundMessageSize = 32 * 1024 * 1024)
}
