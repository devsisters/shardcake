package com.devsisters.shardcake.interfaces

import zio._

trait Logging {
  def logDebug(message: => String): UIO[Unit]
  def logInfo(message: => String): UIO[Unit]
  def logWarning(message: => String): UIO[Unit]
  def logError(message: => String): UIO[Unit]
}

object Logging {
  val debug: ZLayer[Any, Nothing, Has[Logging]] =
    ZLayer.succeed(
      new Logging {
        def logDebug(message: => String): UIO[Unit]   = ZIO.debug(s"[DEBUG] $message").ignore
        def logInfo(message: => String): UIO[Unit]    = ZIO.debug(s"[INFO] $message").ignore
        def logWarning(message: => String): UIO[Unit] = ZIO.debug(s"[WARN] $message").ignore
        def logError(message: => String): UIO[Unit]   = ZIO.debug(s"[ERROR] $message").ignore
      }
    )
}
