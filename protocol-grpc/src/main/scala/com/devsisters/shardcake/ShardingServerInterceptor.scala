package com.devsisters.shardcake

import io.grpc.StatusException
import proteus.ProtobufCodec
import proteus.server.{ GrpcContext, ServerInterceptor }
import zio._
import zio.stream.ZStream

/**
 * Type alias for a proteus [[ServerInterceptor]] scoped to shardcake's effect types
 * ([[IO]] and [[ZStream]] with [[StatusException]] errors) and the default
 * [[GrpcContext]]. Use the smart constructors in the companion object — or implement
 * the trait directly for advanced pre/post/error wrapping.
 */
type ShardingServerInterceptor = ServerInterceptor[
  [A] =>> IO[StatusException, A],
  [A] =>> IO[StatusException, A],
  [A] =>> ZStream[Any, StatusException, A],
  [A] =>> ZStream[Any, StatusException, A],
  GrpcContext,
  GrpcContext
]

object ShardingServerInterceptor {
  private type UnaryEffect[A]  = IO[StatusException, A]
  private type StreamEffect[A] = ZStream[Any, StatusException, A]

  /**
   * An interceptor that does nothing — useful as a starting point or for composition.
   */
  val identity: ShardingServerInterceptor =
    ServerInterceptor.empty[UnaryEffect, StreamEffect, GrpcContext]

  /**
   * Builds an interceptor that runs `effect` before each handler. If the effect fails,
   * the RPC fails with that error and the handler is never called.
   */
  def beforeEach(effect: GrpcContext => IO[StatusException, Unit]): ShardingServerInterceptor =
    new ShardingServerInterceptor {
      def unary[Req: ProtobufCodec, Resp: ProtobufCodec](
        io: GrpcContext => UnaryEffect[Resp]
      ): Req => GrpcContext => UnaryEffect[Resp] =
        _ => ctx => effect(ctx) *> io(ctx)

      def clientStreaming[Req: ProtobufCodec, Resp: ProtobufCodec](
        io: StreamEffect[Req] => GrpcContext => UnaryEffect[Resp]
      ): StreamEffect[Req] => GrpcContext => UnaryEffect[Resp] =
        stream => ctx => effect(ctx) *> io(stream)(ctx)

      def serverStreaming[Req: ProtobufCodec, Resp: ProtobufCodec](
        io: GrpcContext => StreamEffect[Resp]
      ): Req => GrpcContext => StreamEffect[Resp] =
        _ => ctx => ZStream.fromZIO(effect(ctx)) *> io(ctx)

      def bidiStreaming[Req: ProtobufCodec, Resp: ProtobufCodec](
        io: StreamEffect[Req] => GrpcContext => StreamEffect[Resp]
      ): StreamEffect[Req] => GrpcContext => StreamEffect[Resp] =
        stream => ctx => ZStream.fromZIO(effect(ctx)) *> io(stream)(ctx)
    }

  /**
   * Composes a sequence of interceptors into a single one. The first interceptor in the
   * sequence runs outermost (i.e. its pre-logic runs first, its post-logic runs last).
   */
  def compose(interceptors: Seq[ShardingServerInterceptor]): ShardingServerInterceptor =
    interceptors match {
      case Seq()     => identity
      case Seq(only) => only
      case _         =>
        new ShardingServerInterceptor {
          def unary[Req: ProtobufCodec, Resp: ProtobufCodec](
            io: GrpcContext => UnaryEffect[Resp]
          ): Req => GrpcContext => UnaryEffect[Resp] =
            interceptors.foldRight(((_: Req) => io): Req => GrpcContext => UnaryEffect[Resp]) { (i, acc) => req =>
              {
                val applied: Req => GrpcContext => UnaryEffect[Resp] = i.unary[Req, Resp](acc(req))
                applied(req)
              }
            }

          def clientStreaming[Req: ProtobufCodec, Resp: ProtobufCodec](
            io: StreamEffect[Req] => GrpcContext => UnaryEffect[Resp]
          ): StreamEffect[Req] => GrpcContext => UnaryEffect[Resp] =
            interceptors.foldRight(io) { (i, acc) =>
              val step: StreamEffect[Req] => GrpcContext => UnaryEffect[Resp] = i.clientStreaming[Req, Resp](acc)
              step
            }

          def serverStreaming[Req: ProtobufCodec, Resp: ProtobufCodec](
            io: GrpcContext => StreamEffect[Resp]
          ): Req => GrpcContext => StreamEffect[Resp] =
            interceptors.foldRight(((_: Req) => io): Req => GrpcContext => StreamEffect[Resp]) { (i, acc) => req =>
              {
                val applied: Req => GrpcContext => StreamEffect[Resp] = i.serverStreaming[Req, Resp](acc(req))
                applied(req)
              }
            }

          def bidiStreaming[Req: ProtobufCodec, Resp: ProtobufCodec](
            io: StreamEffect[Req] => GrpcContext => StreamEffect[Resp]
          ): StreamEffect[Req] => GrpcContext => StreamEffect[Resp] =
            interceptors.foldRight(io) { (i, acc) =>
              val step: StreamEffect[Req] => GrpcContext => StreamEffect[Resp] = i.bidiStreaming[Req, Resp](acc)
              step
            }
        }
    }
}
