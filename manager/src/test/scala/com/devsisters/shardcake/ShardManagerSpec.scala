package com.devsisters.shardcake

import cats.syntax.all._
import com.devsisters.shardcake.ShardManager.{ PodWithMetadata, ShardManagerState }
import com.devsisters.shardcake.interfaces.{ Logging, Pods, PodsHealth, Storage }
import zio._
import zio.clock.Clock
import zio.console._
import zio.duration._
import zio.stream.ZStream
import zio.test._
import zio.test.environment.TestClock

import java.time.OffsetDateTime

object ShardManagerSpec extends DefaultRunnableSpec {
  private val pod1 = PodWithMetadata(Pod(PodAddress("1", 1), "1.0.0"), OffsetDateTime.MIN)
  private val pod2 = PodWithMetadata(Pod(PodAddress("2", 2), "1.0.0"), OffsetDateTime.MIN)
  private val pod3 = PodWithMetadata(Pod(PodAddress("3", 3), "1.0.0"), OffsetDateTime.MIN)

  override def spec =
    suite("ShardManagerSpec")(
      suite("Unit tests")(
        test("Rebalance unbalanced assignments") {
          val state                        =
            ShardManagerState(
              pods = Map(pod1.pod.address -> pod1, pod2.pod.address -> pod2),
              shards = Map(1 -> Some(pod1.pod.address), 2 -> Some(pod1.pod.address))
            )
          val (assignments, unassignments) = ShardManager.decideAssignmentsForUnbalancedShards(state, 1d)
          assertTrue(assignments.contains(pod2.pod.address)) &&
          assertTrue(assignments.size === 1) &&
          assertTrue(unassignments.contains(pod1.pod.address)) &&
          assertTrue(unassignments.size === 1)
        },
        test("Don't rebalance to pod with older version") {
          val state                        =
            ShardManagerState(
              pods = Map(
                pod1.pod.address -> pod1,
                pod2.pod.address -> pod2.copy(pod = pod2.pod.copy(version = "0.1.2"))
              ), // older version
              shards = Map(1 -> Some(pod1.pod.address), 2 -> Some(pod1.pod.address))
            )
          val (assignments, unassignments) = ShardManager.decideAssignmentsForUnbalancedShards(state, 1d)
          assertTrue(assignments.isEmpty) && assertTrue(unassignments.isEmpty)
        },
        test("Don't rebalance when already well balanced") {
          val state                        =
            ShardManagerState(
              pods = Map(pod1.pod.address -> pod1, pod2.pod.address -> pod2),
              shards = Map(1 -> Some(pod1.pod.address), 2 -> Some(pod2.pod.address))
            )
          val (assignments, unassignments) = ShardManager.decideAssignmentsForUnbalancedShards(state, 1d)
          assertTrue(assignments.isEmpty) && assertTrue(unassignments.isEmpty)
        },
        test("Don't rebalance when only 1 shard difference") {
          val state                        =
            ShardManagerState(
              pods = Map(pod1.pod.address -> pod1, pod2.pod.address -> pod2),
              shards = Map(1 -> Some(pod1.pod.address), 2 -> Some(pod1.pod.address), 3 -> Some(pod2.pod.address))
            )
          val (assignments, unassignments) = ShardManager.decideAssignmentsForUnbalancedShards(state, 1d)
          assertTrue(assignments.isEmpty) && assertTrue(unassignments.isEmpty)
        },
        test("Rebalance when 2 shard difference") {
          val state                        =
            ShardManagerState(
              pods = Map(pod1.pod.address -> pod1, pod2.pod.address -> pod2),
              shards = Map(
                1 -> Some(pod1.pod.address),
                2 -> Some(pod1.pod.address),
                3 -> Some(pod1.pod.address),
                4 -> Some(pod2.pod.address)
              )
            )
          val (assignments, unassignments) = ShardManager.decideAssignmentsForUnbalancedShards(state, 1d)
          assertTrue(assignments.contains(pod2.pod.address)) &&
          assertTrue(assignments.size === 1) &&
          assertTrue(unassignments.contains(pod1.pod.address)) &&
          assertTrue(unassignments.size === 1)
        },
        test("Pick the pod with less shards") {
          val state                        =
            ShardManagerState(
              pods = Map(pod1.pod.address -> pod1, pod2.pod.address -> pod2, pod3.pod.address -> pod3),
              shards = Map(1 -> Some(pod1.pod.address), 2 -> Some(pod1.pod.address), 3 -> Some(pod2.pod.address))
            )
          val (assignments, unassignments) = ShardManager.decideAssignmentsForUnbalancedShards(state, 1d)
          assertTrue(assignments.contains(pod3.pod.address)) &&
          assertTrue(assignments.size === 1) &&
          assertTrue(unassignments.contains(pod1.pod.address)) &&
          assertTrue(unassignments.size === 1)
        },
        test("Don't rebalance if pod list is empty") {
          val state                        =
            ShardManagerState(
              pods = Map(),
              shards = Map(1 -> Some(pod1.pod.address))
            )
          val (assignments, unassignments) = ShardManager.decideAssignmentsForUnbalancedShards(state, 1d)
          assertTrue(assignments.isEmpty) && assertTrue(unassignments.isEmpty)
        },
        test("Balance well when 30 nodes are starting one by one") {
          val state =
            ShardManagerState(
              pods = Map(),
              shards = (1 to 300).map(_ -> None).toMap
            )

          val result =
            (1 to 30).foldLeft(state) { case (state, podNumber) =>
              val podAddress                   = PodAddress("", podNumber)
              val s1                           = state.copy(pods =
                state.pods.updated(podAddress, PodWithMetadata(Pod(podAddress, "v1"), OffsetDateTime.now()))
              )
              val (assignments, unassignments) = ShardManager.decideAssignmentsForUnbalancedShards(s1, 1d)
              val s2                           = unassignments.foldLeft(s1) { case (state, (_, shards)) =>
                shards.foldLeft(state) { case (state, shard) => state.copy(shards = state.shards.updated(shard, None)) }
              }
              val s3                           = assignments.foldLeft(s2) { case (state, (address, shards)) =>
                shards.foldLeft(state) { case (state, shard) =>
                  state.copy(shards = state.shards.updated(shard, Some(address)))
                }
              }
              s3
            }

          val shardsPerPod =
            result.shards.groupBy(_._2).collect { case (Some(address), shards) => address -> shards.keySet }
          assertTrue(shardsPerPod.values.forall(_.size === 10))
        }
      ),
      suite("Simulations")(
        testM("Simulate scaling out scenario") {
          (for {
            // setup 20 pods first
            _           <- simulate((1 to 20).toList.map(i => SimulationEvent.PodRegister(Pod(PodAddress("server", i), "1"))))
            _           <- TestClock.adjust(10 minutes)
            assignments <- ZIO.serviceWith[ShardManager](_.getAssignments)
            // check that all pods are assigned and that all pods have 15 shards each
            assert1      = assertTrue(assignments.values.forall(_.isDefined)) && assertTrue(
                             assignments.groupBy(_._2).forall(_._2.size == 15)
                           )

            // bring 5 new pods
            _           <- simulate((21 to 25).toList.map(i => SimulationEvent.PodRegister(Pod(PodAddress("server", i), "1"))))
            _           <- TestClock.adjust(20 seconds)
            assignments <- ZIO.serviceWith[ShardManager](_.getAssignments)
            // check that new pods received some shards but only 6
            assert2      = assertTrue(assignments.groupBy(_._2).filter(_._1.exists(_.port > 20)).forall(_._2.size == 6))

            _           <- TestClock.adjust(1 minute)
            assignments <- ZIO.serviceWith[ShardManager](_.getAssignments)
            // check that all pods now have 12 shards each
            assert3      = assertTrue(assignments.groupBy(_._2).forall(_._2.size == 12))

          } yield assert1 && assert2 && assert3).provideSomeLayer[TestClock with Clock with Console](shardManager)
        },
        testM("Simulate scaling down scenario") {
          (for {
            // setup 25 pods first
            _           <- simulate((1 to 25).toList.map(i => SimulationEvent.PodRegister(Pod(PodAddress("server", i), "1"))))
            _           <- TestClock.adjust(10 minutes)
            assignments <- ZIO.serviceWith[ShardManager](_.getAssignments)
            // check that all pods are assigned and that all pods have 12 shards each
            assert1      = assertTrue(assignments.values.forall(_.isDefined)) && assertTrue(
                             assignments.groupBy(_._2).forall(_._2.size == 12)
                           )

            // remove 5 pods
            _           <- simulate((21 to 25).toList.map(i => SimulationEvent.PodUnregister(PodAddress("server", i))))
            _           <- TestClock.adjust(1 second)
            assignments <- ZIO.serviceWith[ShardManager](_.getAssignments)
            // check that all shards have been rebalanced already
            assert2      = assertTrue(assignments.values.forall(_.exists(_.port <= 20))) && assertTrue(
                             assignments.groupBy(_._2).forall(_._2.size == 15)
                           )

          } yield assert1 && assert2).provideSomeLayer[TestClock with Clock with Console](shardManager)
        }
      )
    )

  sealed trait SimulationEvent
  object SimulationEvent {
    case class PodRegister(pod: Pod)                 extends SimulationEvent
    case class PodUnregister(podAddress: PodAddress) extends SimulationEvent
  }

  val config: ULayer[Has[ManagerConfig]] = ZLayer.succeed(ManagerConfig.default)

  val storage: ULayer[Has[Storage]] = ZLayer.succeed(new Storage {
    def getAssignments: Task[Map[ShardId, Option[PodAddress]]]                       = ZIO.succeed(Map.empty)
    def saveAssignments(assignments: Map[ShardId, Option[PodAddress]]): Task[Unit]   = ZIO.unit
    def assignmentsStream: ZStream[Any, Throwable, Map[ShardId, Option[PodAddress]]] = ZStream.empty
    def getPods: Task[Map[PodAddress, Pod]]                                          = ZIO.succeed(Map.empty)
    def savePods(pods: Map[PodAddress, Pod]): Task[Unit]                             = ZIO.unit
  })

  val shardManager: ZLayer[Console with Clock, Throwable, Has[ShardManager]] =
    ZLayer.requires[Clock] ++
      ZLayer.requires[Console] ++
      Logging.console ++ config ++ storage ++ Pods.noop >+> PodsHealth.local >>> ShardManager.live

  def simulate(events: List[SimulationEvent]): RIO[Has[ShardManager], Unit] =
    for {
      shardManager <- ZIO.service[ShardManager]
      _            <- ZIO.foreach_(events) {
                        case SimulationEvent.PodRegister(pod)          => shardManager.register(pod)
                        case SimulationEvent.PodUnregister(podAddress) => shardManager.unregister(podAddress)
                      }
    } yield ()
}
