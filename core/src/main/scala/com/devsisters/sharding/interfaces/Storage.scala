package com.devsisters.sharding.interfaces

import com.devsisters.sharding.{ Pod, PodAddress, ShardId }
import zio.{ Ref, Task, ZLayer }
import zio.stream.{ SubscriptionRef, ZStream }

trait Storage {
  def getAssignments: Task[Map[ShardId, Option[PodAddress]]]
  def saveAssignments(assignments: Map[ShardId, Option[PodAddress]]): Task[Unit]
  def assignmentsStream: ZStream[Any, Throwable, Map[Int, Option[PodAddress]]]
  def getPods: Task[Map[PodAddress, Pod]]
  def savePods(pods: Map[PodAddress, Pod]): Task[Unit]
}

object Storage {
  val memory: ZLayer[Any, Nothing, Storage] =
    ZLayer {
      for {
        assignmentsRef <- SubscriptionRef.make(Map.empty[ShardId, Option[PodAddress]])
        podsRef        <- Ref.make(Map.empty[PodAddress, Pod])
      } yield new Storage {
        def getAssignments: Task[Map[ShardId, Option[PodAddress]]]                       = assignmentsRef.get
        def saveAssignments(assignments: Map[ShardId, Option[PodAddress]]): Task[Unit]   = assignmentsRef.set(assignments)
        def assignmentsStream: ZStream[Any, Throwable, Map[ShardId, Option[PodAddress]]] = assignmentsRef.changes
        def getPods: Task[Map[PodAddress, Pod]]                                          = podsRef.get
        def savePods(pods: Map[PodAddress, Pod]): Task[Unit]                             = podsRef.set(pods)
      }
    }
}
