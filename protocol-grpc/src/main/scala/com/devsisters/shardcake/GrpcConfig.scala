package com.devsisters.shardcake

/**
 * The configuration for the gRPC client used in GrpcPods.
 * @param maxInboundMessageSize the maximum message size allowed to be received
 */
case class GrpcConfig(maxInboundMessageSize: Int)
