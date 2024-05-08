package com.devsisters.shardcake

import caliban.QuickAdapter
import caliban.wrappers.Wrappers.printErrors
import zio.http.{ Server => ZServer, _ }
import zio._

object Server {

  /**
   * Start an HTTP server that exposes the Shard Manager GraphQL API
   */
  val run: RIO[ShardManager with ManagerConfig, Nothing] =
    for {
      config      <- ZIO.service[ManagerConfig]
      interpreter <- (GraphQLApi.api @@ printErrors).interpreter
      handlers     = QuickAdapter(interpreter).handlers
      routes       = Routes(
                       Method.ANY / "health"          -> Handler.ok,
                       Method.ANY / "api" / "graphql" -> handlers.api,
                       Method.ANY / "ws" / "graphql"  -> handlers.webSocket
                     ) @@ Middleware.cors
      _           <- ZIO.logInfo(s"Shard Manager server started on port ${config.apiPort}.")
      nothing     <- ZServer
                       .serve(routes.toHttpApp)
                       .provideSome[ShardManager](
                         ZServer.live,
                         ZLayer.succeed(
                           ZServer.Config.default
                             .port(config.apiPort)
                         )
                       )
    } yield nothing
}
