package com.devsisters.shardcake

import com.devsisters.shardcake.protocol.Sharding
import com.devsisters.shardcake.protocol.Sharding._
import io.grpc.{ ManagedChannel, StatusException }
import proteus.client.ZioClientBackend
import zio._
import zio.stream.ZStream

/**
 * Bundles the channel and the per-RPC client functions for a single pod.
 * Holding `channel` lets us shut it down on release; the client functions are
 * thin wrappers around the proteus backend.
 */
private[shardcake] trait PodClient {
  def channel: ManagedChannel
  def assignShards(request: AssignShardsRequest): IO[StatusException, AssignShardsResponse]
  def unassignShards(request: UnassignShardsRequest): IO[StatusException, UnassignShardsResponse]
  def pingShards(request: PingShardsRequest): IO[StatusException, PingShardsResponse]
  def send(request: SendRequest): IO[StatusException, SendResponse]
  def sendStream(requests: ZStream[Any, StatusException, SendRequest]): IO[StatusException, SendResponse]
  def sendAndReceiveStream(request: SendRequest): ZStream[Any, StatusException, SendResponse]
  def sendStreamAndReceiveStream(
    requests: ZStream[Any, StatusException, SendRequest]
  ): ZStream[Any, StatusException, SendResponse]
}

private[shardcake] object PodClient {
  def from(rawChannel: ManagedChannel, backend: ZioClientBackend): PodClient =
    new PodClient {
      val channel: ManagedChannel = rawChannel

      private val assignShardsCall               = backend.client(Sharding.assignShards, Sharding.service)
      private val unassignShardsCall             = backend.client(Sharding.unassignShards, Sharding.service)
      private val pingShardsCall                 = backend.client(Sharding.pingShards, Sharding.service)
      private val sendCall                       = backend.client(Sharding.send, Sharding.service)
      private val sendStreamCall                 = backend.client(Sharding.sendStream, Sharding.service)
      private val sendAndReceiveStreamCall       = backend.client(Sharding.sendAndReceiveStream, Sharding.service)
      private val sendStreamAndReceiveStreamCall =
        backend.client(Sharding.sendStreamAndReceiveStream, Sharding.service)

      def assignShards(request: AssignShardsRequest)                                       = assignShardsCall(request)
      def unassignShards(request: UnassignShardsRequest)                                   = unassignShardsCall(request)
      def pingShards(request: PingShardsRequest)                                           = pingShardsCall(request)
      def send(request: SendRequest)                                                       = sendCall(request)
      def sendStream(requests: ZStream[Any, StatusException, SendRequest])                 = sendStreamCall(requests)
      def sendAndReceiveStream(request: SendRequest)                                       = sendAndReceiveStreamCall(request)
      def sendStreamAndReceiveStream(requests: ZStream[Any, StatusException, SendRequest]) =
        sendStreamAndReceiveStreamCall(requests)
    }
}
