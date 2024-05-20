package com.devsisters.shardcake

import com.devsisters.shardcake.Config
import com.devsisters.shardcake.Server.Message.Ping
import com.devsisters.shardcake.Server.PingPongEntity
import zio._

object Client {
  // self host should not be `localhost` to avoid optimization
  private val config = ZLayer.succeed(Config.default.copy(selfHost = "not-localhost"))

  def send(count: Int, parallelism: Int): Task[Unit] =
    ZIO
      .scoped[Sharding](
        for {
          ping <- Sharding.messenger(PingPongEntity)
          _    <- ZIO.foreachParDiscard(1 to count)(_ => ping.send("ping")(Ping("ping", _))).withParallelism(parallelism)
        } yield ()
      )
      .provide(config, Server.sharding)
}
