package com.devsisters.shardcake

import zio._
import scalapb.zio_grpc.RequestContext
import scalapb.zio_grpc.ZClientInterceptor
import scalapb.zio_grpc.ZTransform

import java.util.concurrent.Executor

/**
 * The configuration for the gRPC client.
 *
 * @param maxInboundMessageSize the maximum message size allowed to be received by the grpc client
 * @param executor a custom executor to pass to grpc-java when creating gRPC clients and servers
 * @param shutdownTimeout the timeout to wait for the gRPC server to shutdown before forcefully shutting it down
 * @param clientInterceptors the interceptors to be used by the gRPC client, e.g for adding tracing or logging
 * @param serverInterceptors the interceptors to be used by the gRPC Server, e.g for adding tracing or logging
 */
case class GrpcConfig(
  maxInboundMessageSize: Int,
  executor: Option[Executor],
  shutdownTimeout: Duration,
  clientInterceptors: Seq[ZClientInterceptor],
  serverInterceptors: Seq[ZTransform[RequestContext, RequestContext]]
)

object GrpcConfig {
  val default: GrpcConfig =
    GrpcConfig(maxInboundMessageSize = 32 * 1024 * 1024, None, 3.seconds, Seq.empty, Seq.empty)
}
