package com.devsisters.shardcake

import caliban.interop.tapir.WebSocketInterpreter
import caliban.{ QuickAdapter, ZHttpAdapter }
import caliban.wrappers.Wrappers.printErrors
import sttp.tapir.json.zio._
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
      apiHandler   = QuickAdapter(interpreter).handlers.api
      routes       = Routes(
                       Method.ANY / "health"          -> Handler.ok,
                       Method.ANY / "api" / "graphql" -> apiHandler,
                       Method.ANY / "ws" / "graphql"  ->
                         ZHttpAdapter.makeWebSocketService(WebSocketInterpreter(interpreter))
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
