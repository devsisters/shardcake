package com.devsisters.shardcake.interfaces

import zio._
import zio.console.Console

trait Logging {
  def logDebugCause(message: => String, cause: => Cause[Any]): UIO[Unit]
  def logInfoCause(message: => String, cause: => Cause[Any]): UIO[Unit]
  def logWarningCause(message: => String, cause: => Cause[Any]): UIO[Unit]
  def logErrorCause(message: => String, cause: => Cause[Any]): UIO[Unit]

  def logDebug(message: => String): UIO[Unit]   = logDebugCause(message, Cause.empty)
  def logInfo(message: => String): UIO[Unit]    = logInfoCause(message, Cause.empty)
  def logWarning(message: => String): UIO[Unit] = logWarningCause(message, Cause.empty)
  def logError(message: => String): UIO[Unit]   = logErrorCause(message, Cause.empty)
}

object Logging {
  val console: ZLayer[Console, Nothing, Has[Logging]] =
    ZIO
      .service[Console.Service]
      .map(console =>
        new Logging {
          def logDebugCause(message: => String, cause: => Cause[Any]): UIO[Unit]   =
            console.putStrLn(s"[DEBUG] $message ${printCause(cause)}").ignore
          def logInfoCause(message: => String, cause: => Cause[Any]): UIO[Unit]    =
            console.putStrLn(s"[INFO] $message ${printCause(cause)}").ignore
          def logWarningCause(message: => String, cause: => Cause[Any]): UIO[Unit] =
            console.putStrLn(s"[WARN] $message ${printCause(cause)}").ignore
          def logErrorCause(message: => String, cause: => Cause[Any]): UIO[Unit]   =
            console.putStrLn(s"[ERROR] $message ${printCause(cause)}").ignore
        }
      )
      .toLayer

  private def printCause(cause: Cause[Any]): String =
    if (cause == Cause.empty) "" else cause.prettyPrint
}
