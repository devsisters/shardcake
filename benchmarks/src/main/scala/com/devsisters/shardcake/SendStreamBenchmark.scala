package com.devsisters.shardcake

import org.openjdk.jmh.annotations._
import zio.{ durationInt, Fiber, Runtime, Unsafe, ZIO }

import java.util.concurrent.TimeUnit

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 3, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 3, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class SendStreamBenchmark {
  private var fiber: Fiber[Any, Any] = _

  @Setup
  def setup(): Unit =
    fiber = Unsafe.unsafe(implicit unsafe =>
      Runtime.default.unsafe.run(Server.run.forkDaemon <* ZIO.sleep(3.seconds)).getOrThrow()
    )

  @TearDown
  def tearDown(): Unit =
    Unsafe.unsafe(implicit unsafe => Runtime.default.unsafe.run(fiber.interrupt))

  // 8 parallel server-streams, each receiving 100 messages → 800 messages per op
  @Benchmark
  def serverStream(): Unit =
    Unsafe.unsafe(implicit unsafe => Runtime.default.unsafe.run(Client.sendStream(8, 100, 8)))
}
