package com.devsisters.sharding.interfaces

import com.devsisters.sharding.{ Pod, PodAddress, ShardId }
import zio.Task
import zio.stream.ZStream

trait Storage {
  def getAssignments: Task[Map[ShardId, Option[PodAddress]]]
  def saveAssignments(assignments: Map[ShardId, Option[PodAddress]]): Task[Unit]
  def assignmentsStream: ZStream[Any, Throwable, Map[Int, Option[PodAddress]]]
  def getPods: Task[Map[PodAddress, Pod]]
  def savePods(pods: Map[PodAddress, Pod]): Task[Unit]
}
