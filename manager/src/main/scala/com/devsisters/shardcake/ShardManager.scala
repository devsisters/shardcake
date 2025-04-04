package com.devsisters.shardcake

import com.devsisters.shardcake.ShardManager._
import com.devsisters.shardcake.interfaces._

import java.time.OffsetDateTime
import scala.util.Random
import zio._
import zio.stream.ZStream

import scala.annotation.tailrec
import scala.collection.compat._

/**
 * A component in charge of assigning and unassigning shards to/from pods
 */
class ShardManager(
  stateRef: Ref.Synchronized[Map[Role, ShardManagerState]],
  rebalanceSemaphores: Ref.Synchronized[Map[Role, Semaphore]],
  eventsHub: Hub[ShardingEvent],
  healthApi: PodsHealth,
  podApi: Pods,
  stateRepository: Storage,
  config: ManagerConfig
) {

  def getAssignments(role: Role): UIO[Map[ShardId, Option[PodAddress]]] =
    stateRef.get.map(_.get(role).fold(Map.empty[ShardId, Option[PodAddress]])(_.shards))

  def getShardingEvents: ZStream[Any, Nothing, ShardingEvent] =
    ZStream.fromHub(eventsHub)

  def register(pod: Pod): Task[Unit] =
    ZIO.ifZIO(healthApi.isAlive(pod))(
      onTrue = for {
        _                <- ZIO.logInfo(s"Registering $pod")
        _                <- ZIO.whenZIO(stateRef.get.map(_.exists { case (role, state) =>
                              state.pods.get(pod.address).exists(_ => role != pod.role)
                            }))(ZIO.fail(new RuntimeException(s"Pod $pod is already registered with a different role")))
        cdt              <- ZIO.succeed(OffsetDateTime.now())
        triggerRebalance <- stateRef.modify { states =>
                              val previous =
                                states.getOrElse(pod.role, ShardManagerState(config.numberOfShards(pod.role)))
                              val state    =
                                previous.copy(pods = previous.pods.updated(pod.address, PodWithMetadata(pod, cdt)))
                              (state.unassignedShards.nonEmpty, states.updated(pod.role, state))
                            }
        _                <- ManagerMetrics.pods.tagged("role", pod.role.name).increment
        _                <- eventsHub.publish(ShardingEvent.PodRegistered(pod))
        _                <- ZIO.whenDiscard(triggerRebalance)(rebalance(pod.role, rebalanceImmediately = false).forkDaemon)
        _                <- persistPods.forkDaemon
      } yield (),
      onFalse = ZIO.logWarning(s"Pod $pod requested to register but is not alive, ignoring") *>
        ZIO.fail(new RuntimeException(s"Pod $pod is not healthy, refusing to register"))
    )

  private def findPod(podAddress: PodAddress): UIO[Option[Pod]] =
    stateRef.get
      .map(_.values.collectFirst {
        case state if state.pods.contains(podAddress) => state.pods.get(podAddress).map(_.pod)
      })
      .map(_.flatten)

  def notifyUnhealthyPod(podAddress: PodAddress, ignoreMetric: Boolean = false): UIO[Unit] =
    ZIO
      .whenCaseZIODiscard(findPod(podAddress)) { case Some(pod) =>
        ManagerMetrics.podHealthChecked.tagged("pod_address", podAddress.toString).increment.unless(ignoreMetric) *>
          eventsHub.publish(ShardingEvent.PodHealthChecked(pod)) *>
          ZIO.unlessZIO(healthApi.isAlive(pod))(
            ZIO.logWarning(s"Pod $podAddress is not alive, unregistering") *> unregister(podAddress)
          )
      }

  def checkAllPodsHealth: UIO[Unit] =
    for {
      pods <- stateRef.get.map(_.values.flatMap(_.pods.keySet))
      _    <- ZIO.foreachParDiscard(pods)(notifyUnhealthyPod(_, ignoreMetric = true)).withParallelism(4)
    } yield ()

  def unregister(podAddress: PodAddress): UIO[Unit] =
    ZIO.whenCaseZIODiscard(findPod(podAddress)) { case Some(pod) =>
      for {
        _             <- ZIO.logInfo(s"Unregistering $podAddress")
        unassignments <- stateRef.modify { states =>
                           val stateOpt = states.get(pod.role)
                           (
                             stateOpt
                               .map(_.shards.collect { case (shard, Some(p)) if p == podAddress => shard }.toSet)
                               .getOrElse(Set.empty),
                             stateOpt.fold(states)(state =>
                               states.updated(
                                 pod.role,
                                 state.copy(
                                   pods = state.pods - podAddress,
                                   shards = state.shards.map { case (k, v) =>
                                     k -> (if (v.contains(podAddress)) None else v)
                                   }
                                 )
                               )
                             )
                           )
                         }
        _             <- ManagerMetrics.pods.tagged("role", pod.role.name).decrement
        _             <- ManagerMetrics.assignedShards
                           .tagged("role", pod.role.name)
                           .tagged("pod_address", podAddress.toString)
                           .decrementBy(unassignments.size)
        _             <- ManagerMetrics.unassignedShards.tagged("role", pod.role.name).incrementBy(unassignments.size)
        _             <- eventsHub.publish(ShardingEvent.PodUnregistered(pod))
        _             <- eventsHub
                           .publish(ShardingEvent.ShardsUnassigned(pod.address, pod.role, unassignments))
                           .when(unassignments.nonEmpty)
        _             <- persistPods.forkDaemon
        _             <- rebalance(pod.role, rebalanceImmediately = true).forkDaemon
      } yield ()
    }

  private def getSemaphore(role: Role): UIO[Semaphore] =
    rebalanceSemaphores.modifyZIO(map =>
      map.get(role) match {
        case Some(s) => ZIO.succeed((s, map))
        case None    => Semaphore.make(1).map(s => (s, map.updated(role, s)))
      }
    )

  private def rebalance(role: Role, rebalanceImmediately: Boolean): UIO[Unit] =
    getSemaphore(role).flatMap(_.withPermit {
      for {
        state                                         <- stateRef.get.map(_.getOrElse(role, ShardManagerState(config.numberOfShards(role))))
        // find which shards to assign and unassign
        (assignments, unassignments)                   = if (rebalanceImmediately || state.unassignedShards.nonEmpty)
                                                           decideAssignmentsForUnassignedShards(state)
                                                         else decideAssignmentsForUnbalancedShards(state, config.rebalanceRate)
        areChanges                                     = assignments.nonEmpty || unassignments.nonEmpty
        _                                             <- (ZIO.logDebug(s"Rebalancing role ${role.name} (rebalanceImmediately=$rebalanceImmediately)") *>
                                                           ManagerMetrics.rebalances.tagged("role", role.name).increment).when(areChanges)
        // ping pods first to make sure they are ready and remove those who aren't
        failedPingedPods                              <- ZIO
                                                           .foreachPar(assignments.keySet ++ unassignments.keySet)(podAddress =>
                                                             podApi
                                                               .ping(podAddress)
                                                               .timeout(config.pingTimeout)
                                                               .someOrFailException
                                                               .fold(_ => Set(podAddress), _ => Set.empty[PodAddress])
                                                           )
                                                           .map(_.flatten)
        shardsToRemove                                 = assignments.collect {
                                                           case (podAddress, shards) if failedPingedPods.contains(podAddress) => shards
                                                         }.toSet.flatten ++
                                                           unassignments.collect {
                                                             case (podAddress, shards) if failedPingedPods.contains(podAddress) => shards
                                                           }.toSet.flatten
        readyAssignments                               = assignments.view.mapValues(_ diff shardsToRemove).filterNot(_._2.isEmpty).toMap
        readyUnassignments                             = unassignments.view.mapValues(_ diff shardsToRemove).filterNot(_._2.isEmpty).toMap
        // do the unassignments first
        failed                                        <- ZIO
                                                           .foreachPar(readyUnassignments.toList) { case (podAddress, shards) =>
                                                             (podApi.unassignShards(podAddress, shards) *>
                                                               updateShardsState(role, shards, None)).foldZIO(
                                                               _ => ZIO.succeed((Set(podAddress), shards)),
                                                               _ =>
                                                                 ManagerMetrics.assignedShards
                                                                   .tagged("role", role.name)
                                                                   .tagged("pod_address", podAddress.toString)
                                                                   .decrementBy(shards.size) *>
                                                                   ManagerMetrics.unassignedShards
                                                                     .tagged("role", role.name)
                                                                     .incrementBy(shards.size) *>
                                                                   eventsHub
                                                                     .publish(ShardingEvent.ShardsUnassigned(podAddress, role, shards))
                                                                     .as((Set.empty, Set.empty))
                                                             )
                                                           }
                                                           .map(_.unzip)
                                                           .map { case (pods, shards) => (pods.flatten[PodAddress].toSet, shards.flatten[ShardId].toSet) }
        (failedUnassignedPods, failedUnassignedShards) = failed
        // remove assignments of shards that couldn't be unassigned, as well as faulty pods
        filteredAssignments                            = (readyAssignments -- failedUnassignedPods).map { case (podAddress, shards) =>
                                                           podAddress -> (shards diff failedUnassignedShards)
                                                         }
        // then do the assignments
        failedAssignedPods                            <- ZIO
                                                           .foreachPar(filteredAssignments.toList) { case (podAddress, shards) =>
                                                             (podApi.assignShards(podAddress, shards) *>
                                                               updateShardsState(role, shards, Some(podAddress)))
                                                               .foldZIO(
                                                                 _ => ZIO.succeed(Set(podAddress)),
                                                                 _ =>
                                                                   ManagerMetrics.assignedShards
                                                                     .tagged("role", role.name)
                                                                     .tagged("pod_address", podAddress.toString)
                                                                     .incrementBy(shards.size) *>
                                                                     ManagerMetrics.unassignedShards
                                                                       .tagged("role", role.name)
                                                                       .decrementBy(shards.size) *>
                                                                     eventsHub
                                                                       .publish(ShardingEvent.ShardsAssigned(podAddress, role, shards))
                                                                       .as(Set.empty)
                                                               )
                                                           }
                                                           .map(_.flatten[PodAddress].toSet)
        failedPods                                     = failedPingedPods ++ failedUnassignedPods ++ failedAssignedPods
        // check if failing pods are still up
        _                                             <- ZIO.foreachDiscard(failedPods)(notifyUnhealthyPod(_)).forkDaemon
        _                                             <- ZIO.logWarning(s"Failed to rebalance pods for role ${role.name}: $failedPods").when(failedPods.nonEmpty)
        // retry rebalancing later if there was any failure
        _                                             <- (Clock.sleep(config.rebalanceRetryInterval) *> rebalance(role, rebalanceImmediately)).forkDaemon
                                                           .when(failedPods.nonEmpty && rebalanceImmediately)
        // persist state changes to Redis
        _                                             <- persistAssignments(role).forkDaemon.when(areChanges)
      } yield ()
    })

  private def withRetry[E, A](zio: IO[E, A]): UIO[Unit] =
    zio
      .retry[Any, Any](Schedule.spaced(config.persistRetryInterval) && Schedule.recurs(config.persistRetryCount))
      .ignore

  private def persistAssignments(role: Role): UIO[Unit] =
    withRetry(
      stateRef.get.flatMap(states =>
        stateRepository.saveAssignments(role, states.get(role).map(_.shards).getOrElse(Map.empty))
      )
    )

  private def persistAllAssignments: UIO[Unit] =
    stateRef.get.flatMap(states => ZIO.foreachDiscard(states.keys)(persistAssignments))

  private def persistPods: UIO[Unit] =
    withRetry(
      stateRef.get.flatMap(states =>
        stateRepository.savePods(states.values.flatMap(_.pods.map { case (k, v) => (k, v.pod) }).toMap)
      )
    )

  private def updateShardsState(role: Role, shards: Set[ShardId], podAddress: Option[PodAddress]): Task[Unit] =
    stateRef.updateZIO { states =>
      val previous = states.get(role)
      ZIO
        .whenCase((previous, podAddress)) {
          case (Some(state), Some(podAddress)) if !state.pods.contains(podAddress) =>
            ZIO.fail(new Exception(s"Pod $podAddress is not registered"))
        }
        .as(
          previous.fold(states)(state =>
            states.updated(
              role,
              state.copy(shards = state.shards.map { case (shard, assignment) =>
                shard -> (if (shards.contains(shard)) podAddress else assignment)
              })
            )
          )
        )
    }
}

object ShardManager {

  /**
   * A layer that starts the Shard Manager process
   */
  val live: ZLayer[PodsHealth with Pods with Storage with ManagerConfig, Throwable, ShardManager] =
    ZLayer.scoped {
      for {
        config              <- ZIO.service[ManagerConfig]
        stateRepository     <- ZIO.service[Storage]
        healthApi           <- ZIO.service[PodsHealth]
        podApi              <- ZIO.service[Pods]
        oldPods             <- stateRepository.getPods
        // remove unhealthy pods on startup
        aliveStatuses       <- ZIO.foreachPar(oldPods.values)(pod => healthApi.isAlive(pod).map(pod -> _))
        (failedPods, pods)   = aliveStatuses.partitionMap {
                                 case (pod, false) => Left(pod)
                                 case (pod, true)  => Right(pod)
                               }
        _                   <- ZIO.whenDiscard(failedPods.nonEmpty)(
                                 ZIO.logInfo(s"Ignoring pods that are no longer alive ${failedPods.mkString("[", ", ", "]")}")
                               )
        _                   <- ZIO.whenDiscard(pods.nonEmpty)(
                                 ZIO.logInfo(s"Recovered pods ${pods.mkString("[", ", ", "]")}")
                               )
        podsByAddress        = pods.map(p => p.address -> p).toMap
        podsByRole           = pods.groupBy(_.role)
        assignments         <- ZIO
                                 .foreach(podsByRole.keySet) { role =>
                                   for {
                                     oldAssignments       <- stateRepository.getAssignments(role)
                                     (failed, assignments) = partitionMap(oldAssignments) {
                                                               case assignment @ (_, Some(address))
                                                                   if podsByAddress.contains(address) =>
                                                                 Right(assignment)
                                                               case assignment => Left(assignment)
                                                             }
                                     failedAssignments     = failed.collect { case (shard, Some(addr)) => shard -> addr }
                                     _                    <-
                                       ZIO.whenDiscard(failedAssignments.nonEmpty)(
                                         ZIO.logWarning(
                                           s"Ignoring assignments for pods that are no longer alive for role ${role.name}: ${failedAssignments
                                             .mkString("[", ", ", "]")}"
                                         )
                                       )
                                     _                    <-
                                       ZIO.whenDiscard(assignments.nonEmpty)(
                                         ZIO.logInfo(
                                           s"Recovered assignments for role ${role.name}: ${assignments.mkString("[", ", ", "]")}"
                                         )
                                       )
                                   } yield role -> assignments
                                 }
                                 .map(_.toMap)
        cdt                 <- ZIO.succeed(OffsetDateTime.now())
        initialStates        = podsByRole.map { case (role, pods) =>
                                 role -> ShardManagerState(
                                   pods.map(pod => pod.address -> PodWithMetadata(pod, cdt)).toMap,
                                   (1 to config.numberOfShards(role)).map(_ -> None).toMap ++
                                     assignments.getOrElse(role, Map.empty),
                                   config.numberOfShards(role)
                                 )
                               }
        _                   <- ZIO
                                 .foreachDiscard(initialStates) { case (role, state) =>
                                   ManagerMetrics.pods.tagged("role", role.name).incrementBy(state.pods.size) *>
                                     ZIO.foreachDiscard(state.shards) { case (_, podAddressOpt) =>
                                       podAddressOpt match {
                                         case Some(podAddress) =>
                                           ManagerMetrics.assignedShards
                                             .tagged("role", role.name)
                                             .tagged("pod_address", podAddress.toString)
                                             .increment
                                         case None             =>
                                           ManagerMetrics.unassignedShards
                                             .tagged("role", role.name)
                                             .increment
                                       }
                                     }
                                 }
        state               <- Ref.Synchronized.make(initialStates)
        rebalanceSemaphores <- Ref.Synchronized.make(Map.empty[Role, Semaphore])
        eventsHub           <- Hub.unbounded[ShardingEvent]
        shardManager         = new ShardManager(
                                 stateRef = state,
                                 rebalanceSemaphores = rebalanceSemaphores,
                                 eventsHub = eventsHub,
                                 healthApi = healthApi,
                                 podApi = podApi,
                                 stateRepository = stateRepository,
                                 config = config
                               )
        _                   <- ZIO.addFinalizer {
                                 shardManager.persistAllAssignments.catchAllCause(cause =>
                                   ZIO.logWarningCause("Failed to persist assignments on shutdown", cause)
                                 ) *>
                                   shardManager.persistPods.catchAllCause(cause =>
                                     ZIO.logWarningCause("Failed to persist pods on shutdown", cause)
                                   )
                               }
        _                   <- shardManager.persistPods.forkDaemon
        // rebalance immediately if there are unassigned shards
        _                   <- ZIO.foreachDiscard(initialStates) { case (role, state) =>
                                 shardManager.rebalance(role, rebalanceImmediately = state.unassignedShards.nonEmpty).forkDaemon
                               }
        // start a regular rebalance at the given interval
        _                   <- state.get
                                 .flatMap(states =>
                                   ZIO.foreachParDiscard(states.keySet)(shardManager.rebalance(_, rebalanceImmediately = false))
                                 )
                                 .repeat(Schedule.spaced(config.rebalanceInterval))
                                 .forkDaemon
        _                   <- shardManager.getShardingEvents.mapZIO(event => ZIO.logInfo(event.toString)).runDrain.forkDaemon
        _                   <- shardManager.checkAllPodsHealth.repeat(Schedule.spaced(config.podHealthCheckInterval)).forkDaemon
        _                   <- ZIO.logInfo("Shard Manager loaded")
      } yield shardManager
    }

  // reimplement Map.partitionMap because it does not exist in 2.12
  private def partitionMap[K, V, VL <: V, VR <: V](map: Map[K, V])(partition: ((K, V)) => Either[(K, VL), (K, VR)]) = {
    val left  = Map.newBuilder[K, VL]
    val right = Map.newBuilder[K, VR]

    map.iterator.foreach { kv =>
      partition(kv) match {
        case Left(kvl)  => left += kvl
        case Right(kvr) => right += kvr
      }
    }

    (left.result(), right.result())
  }

  implicit def listOrder[A](implicit ev: Ordering[A]): Ordering[List[A]] = (xs: List[A], ys: List[A]) => {
    @tailrec def loop(xs: List[A], ys: List[A]): Int =
      xs match {
        case Nil     =>
          if (ys.isEmpty) 0 else -1
        case x :: xs =>
          ys match {
            case Nil     => 1
            case y :: ys =>
              val n = ev.compare(x, y)
              if (n != 0) n else loop(xs, ys)
          }
      }

    if (xs eq ys) 0 else loop(xs, ys)
  }

  case class ShardManagerState(
    pods: Map[PodAddress, PodWithMetadata],
    shards: Map[ShardId, Option[PodAddress]],
    numberOfShards: Int
  ) {
    lazy val unassignedShards: Set[ShardId]    = shards.collect { case (k, None) => k }.toSet
    lazy val averageShardsPerPod: ShardId      = if (pods.nonEmpty) shards.size / pods.size else 0
    private lazy val podVersions               = pods.values.toList.map(extractVersion)
    lazy val maxVersion: Option[List[ShardId]] = podVersions.maxOption
    lazy val allPodsHaveMaxVersion: Boolean    = podVersions.forall(maxVersion.contains)

    lazy val shardsPerPod: Map[PodAddress, Set[ShardId]] =
      pods.map { case (k, _) => k -> Set.empty[ShardId] } ++
        shards.groupBy(_._2).collect { case (Some(address), shards) => address -> shards.keySet }
  }
  object ShardManagerState {
    def apply(numberOfShards: Int): ShardManagerState =
      ShardManagerState(Map.empty, (1 to numberOfShards).map(_ -> None).toMap, numberOfShards)
  }

  case class PodWithMetadata(pod: Pod, registeredAt: OffsetDateTime)

  sealed trait ShardingEvent
  object ShardingEvent {
    case class ShardsAssigned(podAddress: PodAddress, role: Role, shards: Set[ShardId])   extends ShardingEvent {
      override def toString: String =
        s"ShardsAssigned(pod=$podAddress, role=${role.name}, shards=${renderShardIds(shards)})"
    }
    case class ShardsUnassigned(podAddress: PodAddress, role: Role, shards: Set[ShardId]) extends ShardingEvent {
      override def toString: String =
        s"ShardsUnassigned(pod=$podAddress, role=${role.name}, shards=${renderShardIds(shards)})"
    }
    case class PodRegistered(pod: Pod)                                                    extends ShardingEvent
    case class PodUnregistered(pod: Pod)                                                  extends ShardingEvent
    case class PodHealthChecked(pod: Pod)                                                 extends ShardingEvent
  }

  def decideAssignmentsForUnassignedShards(
    state: ShardManagerState
  ): (Map[PodAddress, Set[ShardId]], Map[PodAddress, Set[ShardId]]) =
    pickNewPods(state.unassignedShards.toList, state, rebalanceImmediately = true, 1.0)

  def decideAssignmentsForUnbalancedShards(
    state: ShardManagerState,
    rebalanceRate: Double
  ): (Map[PodAddress, Set[ShardId]], Map[PodAddress, Set[ShardId]]) = {
    val extraShardsToAllocate   =
      if (state.allPodsHaveMaxVersion) { // don't do regular rebalance in the middle of a rolling update
        state.shardsPerPod.flatMap { case (_, shards) =>
          // count how many extra shards compared to the average
          val extraShards = (shards.size - state.averageShardsPerPod).max(0)
          Random.shuffle(shards).take(extraShards)
        }.toSet
      } else Set.empty
    val sortedShardsToRebalance = extraShardsToAllocate.toList.sortBy { shard =>
      // handle unassigned shards first, then shards on the pods with most shards, then shards on old pods
      state.shards.get(shard).flatten.fold((Int.MinValue, OffsetDateTime.MIN)) { podAddress =>
        (
          state.shardsPerPod.get(podAddress).fold(Int.MinValue)(-_.size),
          state.pods.get(podAddress).fold(OffsetDateTime.MIN)(_.registeredAt)
        )
      }
    }
    pickNewPods(sortedShardsToRebalance, state, rebalanceImmediately = false, rebalanceRate)
  }

  private def pickNewPods(
    shardsToRebalance: List[ShardId],
    state: ShardManagerState,
    rebalanceImmediately: Boolean,
    rebalanceRate: Double
  ): (Map[PodAddress, Set[ShardId]], Map[PodAddress, Set[ShardId]]) = {
    val (_, assignments)    = shardsToRebalance.foldLeft((state.shardsPerPod, List.empty[(ShardId, PodAddress)])) {
      case ((shardsPerPod, assignments), shard) =>
        val unassignedPods = assignments.flatMap { case (shard, _) =>
          state.shards.get(shard).flatten[PodAddress]
        }.toSet
        // find pod with least amount of shards
        shardsPerPod
          // keep only pods with the max version
          .filter { case (podAddress, _) =>
            state.maxVersion.forall(max => state.pods.get(podAddress).map(extractVersion).forall(_ == max))
          }
          // don't assign too many shards to the same pods, unless we need rebalance immediately
          .filter { case (podAddress, _) =>
            rebalanceImmediately ||
              assignments.count { case (_, p) => p == podAddress } < state.shards.size * rebalanceRate
          }
          // don't assign to a pod that was unassigned in the same rebalance
          .filterNot { case (podAddress, _) => unassignedPods.contains(podAddress) }
          .minByOption(_._2.size) match {
          case Some((podAddress, shards)) =>
            val oldPodAddress = state.shards.get(shard).flatten
            // if old pod is same as new pod, don't change anything
            if (oldPodAddress.contains(podAddress))
              (shardsPerPod, assignments)
            // if the new pod has more, as much, or only 1 less shard than the old pod, don't change anything
            else if (
              shardsPerPod.get(podAddress).fold(0)(_.size) + 1 >= oldPodAddress.fold(Int.MaxValue)(
                shardsPerPod.getOrElse(_, Nil).size
              )
            )
              (shardsPerPod, assignments)
            // otherwise, create a new assignment
            else {
              val unassigned =
                oldPodAddress.fold(shardsPerPod)(oldPodAddress =>
                  shardsPerPod.updatedWith(oldPodAddress)(_.map(_ - shard))
                )
              (unassigned.updated(podAddress, shards + shard), (shard, podAddress) :: assignments)
            }
          case None                       => (shardsPerPod, assignments)
        }
    }
    val unassignments       = assignments.flatMap { case (shard, _) => state.shards.get(shard).flatten.map(shard -> _) }
    val assignmentsPerPod   = assignments.groupBy(_._2).map { case (k, v) => k -> v.map(_._1).toSet }
    val unassignmentsPerPod = unassignments.groupBy(_._2).map { case (k, v) => k -> v.map(_._1).toSet }
    (assignmentsPerPod, unassignmentsPerPod)
  }

  private def extractVersion(pod: PodWithMetadata): List[Int] =
    pod.pod.version.split("[.-]").toList.flatMap(_.toIntOption)
}
