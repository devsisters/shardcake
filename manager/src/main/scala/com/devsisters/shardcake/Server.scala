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
                         case _ -> !! / "health"          => Handler.ok.toHttp
                         case _ -> !! / "api" / "graphql" => ZHttpAdapter.makeHttpService(HttpInterpreter(interpreter))
                         case _ -> !! / "ws" / "graphql"  =>
                           ZHttpAdapter.makeWebSocketService(WebSocketInterpreter(interpreter))
                       }
                       .withDefaultErrorResponse @@ HttpAppMiddleware.cors()
      nothing     <-
        ZIO
          .scoped(
            ZServer
              .serve(routes)
              .flatMap(port =>
                ZIO.logInfo(s"Shard Manager server started on port $port.") *>
                  ZIO.never
              )
              .provideSomeLayer[ShardManager with Scope](
                ZServer.defaultWithPort(config.apiPort)
              )
          )
          .forever
    } yield nothing
}
