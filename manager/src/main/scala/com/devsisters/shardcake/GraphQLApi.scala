package com.devsisters.shardcake

import caliban.GraphQL.graphQL
import caliban.schema.GenericSchema
import caliban.{ GraphQL, RootResolver }
import com.devsisters.shardcake.ShardManager.ShardingEvent
import zio.stream.ZStream
import zio.{ RIO, URIO, ZIO }

object GraphQLApi extends GenericSchema[ShardManager] {

  case class Assignment(shardId: ShardId, pod: Option[PodAddress])
  case class Queries(getAssignments: URIO[ShardManager, List[Assignment]])
  case class PodAddressArgs(podAddress: PodAddress)
  case class Mutations(
    register: Pod => RIO[ShardManager, Unit],
    unregister: Pod => RIO[ShardManager, Unit],
    notifyUnhealthyPod: PodAddressArgs => URIO[ShardManager, Unit]
  )
  case class Subscriptions(events: ZStream[ShardManager, Nothing, ShardingEvent])

  val api: GraphQL[ShardManager] =
    graphQL[ShardManager, Queries, Mutations, Subscriptions](
      RootResolver(
        Queries(ZIO.serviceWithZIO(_.getAssignments.map(_.map { case (k, v) => Assignment(k, v) }.toList))),
        Mutations(
          pod => ZIO.serviceWithZIO(_.register(pod)),
          pod => ZIO.serviceWithZIO(_.unregister(pod.address)),
          args => ZIO.serviceWithZIO(_.notifyUnhealthyPod(args.podAddress))
        ),
        Subscriptions(ZStream.serviceWithStream(_.getShardingEvents))
      )
    )
}
