package com.devsisters.shardcake

import caliban.ZHttpAdapter
import caliban.wrappers.Wrappers.printErrors
import zhttp.http._
import zhttp.http.Middleware.cors
import zhttp.service.{ EventLoopGroup, Server => ZServer }
import zhttp.service.server.ServerChannelFactory
import zio._

object Server {

  /**
   * Start an HTTP server that exposes the Shard Manager GraphQL API
   */
  val run: RIO[ShardManager with ManagerConfig, Nothing] =
    for {
      config      <- ZIO.service[ManagerConfig]
      interpreter <- (GraphQLApi.api @@ printErrors).interpreter
      server       = ZServer.port(config.apiPort) ++ ZServer.app(Http.collectHttp[Request] {
                       case _ -> !! / "health"          => Http.succeed(Response.ok)
                       case _ -> !! / "api" / "graphql" => ZHttpAdapter.makeHttpService(interpreter)
                       case _ -> !! / "ws" / "graphql"  => ZHttpAdapter.makeWebSocketService(interpreter)
                     } @@ cors())
      nothing     <- ZIO
                       .scoped(
                         server.make
                           .flatMap(start =>
                             ZIO.logInfo(s"Shard Manager server started on port ${start.port}.") *>
                               ZIO.never
                           )
                           .provideSomeLayer[ShardManager with Scope](EventLoopGroup.auto(0) ++ ServerChannelFactory.auto)
                       )
                       .forever
    } yield nothing
}
