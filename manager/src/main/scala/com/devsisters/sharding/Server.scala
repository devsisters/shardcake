package com.devsisters.sharding

import caliban.ZHttpAdapter
import caliban.wrappers.Wrappers.printErrors
import zhttp.http.Middleware.cors
import zhttp.http._
import zhttp.service.{ Server => ZServer }
import zio._

object Server {
  val run: RIO[ShardManager with ManagerConfig, Nothing] =
    for {
      config      <- ZIO.service[ManagerConfig]
      interpreter <- (GraphQLApi.api @@ printErrors).interpreter
      nothing     <- ZServer
                       .start(
                         config.apiPort,
                         Http.collectHttp[Request] {
                           case _ -> !! / "health"          => Http.succeed(Response.ok)
                           case _ -> !! / "api" / "graphql" => ZHttpAdapter.makeHttpService(interpreter)
                           case _ -> !! / "ws" / "graphql"  =>
                             ZHttpAdapter.makeWebSocketService(interpreter, keepAliveTime = Some(30 seconds))
                         } @@ cors()
                       )
                       .forever
    } yield nothing
}
