package com.devsisters.shardcake

import org.openjdk.jmh.annotations._
import zio.{ durationInt, Fiber, Runtime, Unsafe, ZIO }

import java.util.concurrent.TimeUnit

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class SendBenchmark {
  private var fiber: Fiber[Any, Any] = _

  @Setup
  def setup(): Unit =
    fiber = Unsafe.unsafe(implicit unsafe =>
      Runtime.default.unsafe.run(Server.run.forkDaemon <* ZIO.sleep(3.seconds)).getOrThrow()
    )

  @TearDown
  def tearDown(): Unit =
    Unsafe.unsafe(implicit unsafe => Runtime.default.unsafe.run(fiber.interrupt))

  @Benchmark
  def send(): Unit =
    Unsafe.unsafe(implicit unsafe => Runtime.default.unsafe.run(Client.send(100, 8)))
}
