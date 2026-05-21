package com.devsisters.shardcake

import io.grpc.ForwardingClientCall.SimpleForwardingClientCall
import io.grpc.{ CallOptions, Channel, ClientCall, ClientInterceptor, Metadata, MethodDescriptor }

/**
 * Helpers for building common [[io.grpc.ClientInterceptor]] instances to put in
 * [[GrpcConfig.clientInterceptors]].
 */
object ShardingClientInterceptor {

  /**
   * Builds a client interceptor that lets you mutate the outgoing request headers before every
   * call. The `update` callback receives the [[Metadata]] for the current call and may put,
   * remove or read entries on it.
   */
  def headersUpdater(update: Metadata => Unit): ClientInterceptor =
    new ClientInterceptor {
      def interceptCall[ReqT, RespT](
        method: MethodDescriptor[ReqT, RespT],
        callOptions: CallOptions,
        next: Channel
      ): ClientCall[ReqT, RespT] =
        new SimpleForwardingClientCall[ReqT, RespT](next.newCall(method, callOptions)) {
          override def start(responseListener: ClientCall.Listener[RespT], headers: Metadata): Unit = {
            update(headers)
            super.start(responseListener, headers)
          }
        }
    }
}
