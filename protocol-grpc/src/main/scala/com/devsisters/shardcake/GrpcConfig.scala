package com.devsisters.shardcake

import java.util.concurrent.Executor

/**
 * The configuration for the gRPC client.
 *
 * @param maxInboundMessageSize the maximum message size allowed to be received by the grpc client
 * @param executor a custom executor to pass to grpc-java when creating gRPC clients and servers
 */
case class GrpcConfig(maxInboundMessageSize: Int, executor: Option[Executor])

object GrpcConfig {
  val default: GrpcConfig =
    GrpcConfig(maxInboundMessageSize = 32 * 1024 * 1024, None)
}
