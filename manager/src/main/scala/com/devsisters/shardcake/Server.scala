package com.devsisters.shardcake

import caliban.ZHttpAdapter
import caliban.wrappers.Wrappers.printErrors
import zhttp.http.Middleware.cors
import zhttp.http._
import zhttp.service.{ Server => ZServer }
import zio._
import zio.clock.Clock
import zio.console.Console

object Server {

  /**
   * Start an HTTP server that exposes the Shard Manager GraphQL API
   */
  val run: RIO[Has[ShardManager] with Has[ManagerConfig] with Clock with Console, Nothing] =
    for {
      config      <- ZIO.service[ManagerConfig]
      interpreter <- (GraphQLApi.api @@ printErrors).interpreter
      nothing     <- ZServer
                       .start(
                         config.apiPort,
                         Http.collectHttp[Request] {
                           case _ -> !! / "health"          => Http.succeed(Response.ok)
                           case _ -> !! / "api" / "graphql" => ZHttpAdapter.makeHttpService(interpreter)
                           case _ -> !! / "ws" / "graphql"  => ZHttpAdapter.makeWebSocketService(interpreter)
                         } @@ cors()
                       )
                       .forever
    } yield nothing
}
