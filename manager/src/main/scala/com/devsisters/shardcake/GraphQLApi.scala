package com.devsisters.shardcake

import caliban.GraphQL.graphQL
import caliban.schema.GenericSchema
import caliban.{ GraphQL, RootResolver }
import com.devsisters.shardcake.ShardManager.ShardingEvent
import zio.stream.ZStream
import zio.{ Has, RIO, URIO, ZIO }

object GraphQLApi extends GenericSchema[Has[ShardManager]] {

  case class Assignment(shardId: ShardId, pod: Option[PodAddress])
  case class Queries(getAssignments: URIO[Has[ShardManager], List[Assignment]])
  case class PodAddressArgs(podAddress: PodAddress)
  case class Mutations(
    register: Pod => RIO[Has[ShardManager], Unit],
    unregister: Pod => RIO[Has[ShardManager], Unit],
    notifyUnhealthyPod: PodAddressArgs => URIO[Has[ShardManager], Unit]
  )
  case class Subscriptions(events: ZStream[Has[ShardManager], Nothing, ShardingEvent])

  val api: GraphQL[Has[ShardManager]] =
    graphQL[Has[ShardManager], Queries, Mutations, Subscriptions](
      RootResolver(
        Queries(ZIO.serviceWith(_.getAssignments.map(_.map { case (k, v) => Assignment(k, v) }.toList))),
        Mutations(
          pod => ZIO.serviceWith(_.register(pod)),
          pod => ZIO.serviceWith(_.unregister(pod.address)),
          args => ZIO.serviceWith(_.notifyUnhealthyPod(args.podAddress))
        ),
        Subscriptions(ZStream.serviceWithStream(_.getShardingEvents))
      )
    )
}
