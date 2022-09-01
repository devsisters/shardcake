package com.devsisters.shardcake.interfaces

import com.devsisters.shardcake.{ Pod, PodAddress, ShardId }
import zio.{ Has, Ref, Task, ZLayer }
import zio.stream.{ SubscriptionRef, ZStream }

/**
 * An interface for storing the state of shards, pods and their assignments.
 */
trait Storage {

  /**
   * Get the current state of shard assignments to pods
   */
  def getAssignments: Task[Map[ShardId, Option[PodAddress]]]

  /**
   * Save the current state of shard assignments to pods
   */
  def saveAssignments(assignments: Map[ShardId, Option[PodAddress]]): Task[Unit]

  /**
   * A stream that will emit the state of shard assignments whenever it changes
   */
  def assignmentsStream: ZStream[Any, Throwable, Map[Int, Option[PodAddress]]]

  /**
   * Get the list of existing pods
   */
  def getPods: Task[Map[PodAddress, Pod]]

  /**
   * Save the list of existing pods
   */
  def savePods(pods: Map[PodAddress, Pod]): Task[Unit]
}

object Storage {

  /**
   * A layer that stores data in-memory.
   * This is useful for testing with a single pod only.
   */
  val memory: ZLayer[Any, Nothing, Has[Storage]] =
    (for {
      assignmentsRef <- SubscriptionRef.make(Map.empty[ShardId, Option[PodAddress]])
      podsRef        <- Ref.make(Map.empty[PodAddress, Pod])
    } yield new Storage {
      def getAssignments: Task[Map[ShardId, Option[PodAddress]]]                       = assignmentsRef.ref.get
      def saveAssignments(assignments: Map[ShardId, Option[PodAddress]]): Task[Unit]   =
        assignmentsRef.ref.set(assignments)
      def assignmentsStream: ZStream[Any, Throwable, Map[ShardId, Option[PodAddress]]] = assignmentsRef.changes
      def getPods: Task[Map[PodAddress, Pod]]                                          = podsRef.get
      def savePods(pods: Map[PodAddress, Pod]): Task[Unit]                             = podsRef.set(pods)
    }).toLayer
}
