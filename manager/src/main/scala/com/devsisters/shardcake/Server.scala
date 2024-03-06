package com.devsisters.shardcake

import caliban.ZHttpAdapter
import caliban.wrappers.Wrappers.printErrors
import sttp.tapir.json.zio._
import zio.http.{ Server => ZServer, _ }
import zio._
import caliban.interop.tapir.HttpInterpreter
import caliban.interop.tapir.WebSocketInterpreter

object Server {

  /**
   * Start an HTTP server that exposes the Shard Manager GraphQL API
   */
  val run: RIO[ShardManager with ManagerConfig, Nothing] =
    for {
      config      <- ZIO.service[ManagerConfig]
      interpreter <- (GraphQLApi.api @@ printErrors).interpreter
      routes       = Http
                       .collectHttp[Request] {
                         case _ -> Root / "health"          => Handler.ok.toHttp
                         case _ -> Root / "api" / "graphql" => ZHttpAdapter.makeHttpService(HttpInterpreter(interpreter))
                         case _ -> Root / "ws" / "graphql"  =>
                           ZHttpAdapter.makeWebSocketService(WebSocketInterpreter(interpreter))
                       } @@ HttpAppMiddleware.cors()
      _           <- ZIO.logInfo(s"Shard Manager server started on port ${config.apiPort}.")
      nothing     <- ZServer
                       .serve(routes)
                       .provideSome[ShardManager](
                         ZServer.live,
                         ZLayer.succeed(
                           ZServer.Config.default
                             .port(config.apiPort)
                             .withWebSocketConfig(ZHttpAdapter.defaultWebSocketConfig)
                         )
                       )
    } yield nothing
}
