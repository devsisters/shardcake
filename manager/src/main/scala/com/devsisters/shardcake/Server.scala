package com.devsisters.shardcake

import caliban.ZHttpAdapter
import caliban.wrappers.Wrappers.printErrors
import com.devsisters.shardcake.interfaces.Logging
import zhttp.http.Middleware.cors
import zhttp.http._
import zhttp.service.{ EventLoopGroup, Server => ZServer }
import zhttp.service.server.ServerChannelFactory
import zio._
import zio.clock.Clock
import zio.console.Console

object Server {

  /**
   * Start an HTTP server that exposes the Shard Manager GraphQL API
   */
  val run: RIO[Has[ShardManager] with Has[ManagerConfig] with Has[Logging] with Clock with Console, Nothing] =
    for {
      config      <- ZIO.service[ManagerConfig]
      logger      <- ZIO.service[Logging]
      interpreter <- (GraphQLApi.api @@ printErrors).interpreter
      server       = ZServer.port(config.apiPort) ++ ZServer.app(Http.collectHttp[Request] {
                       case _ -> !! / "health"          => Http.succeed(Response.ok)
                       case _ -> !! / "api" / "graphql" => ZHttpAdapter.makeHttpService(interpreter)
                       case _ -> !! / "ws" / "graphql"  => ZHttpAdapter.makeWebSocketService(interpreter)
                     } @@ cors())
      nothing     <- server.make
                       .use(start =>
                         logger.logInfo(s"Server Manager started on port ${start.port}")
                           *> ZIO.never
                       )
                       .provideSomeLayer[Console with Has[ShardManager] with Clock](
                         ServerChannelFactory.auto ++ EventLoopGroup.auto(0)
                       )
                       .forever
    } yield nothing
}
