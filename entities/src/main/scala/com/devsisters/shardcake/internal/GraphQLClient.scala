package com.devsisters.shardcake.internal

import caliban.client.FieldBuilder._
import caliban.client._
import caliban.client.__Value._

private[shardcake] object GraphQLClient {

  type Assignment
  object Assignment {
    def shardId: SelectionBuilder[Assignment, Int]                                                       =
      _root_.caliban.client.SelectionBuilder.Field("shardId", Scalar())
    def pod[A](innerSelection: SelectionBuilder[PodAddress, A]): SelectionBuilder[Assignment, Option[A]] =
      _root_.caliban.client.SelectionBuilder.Field("pod", OptionOf(Obj(innerSelection)))
  }

  type PodAddress
  object PodAddress {
    def host: SelectionBuilder[PodAddress, String] = _root_.caliban.client.SelectionBuilder.Field("host", Scalar())
    def port: SelectionBuilder[PodAddress, Int]    = _root_.caliban.client.SelectionBuilder.Field("port", Scalar())
  }

  type ShardsAssigned
  object ShardsAssigned {
    def pod[A](innerSelection: SelectionBuilder[PodAddress, A]): SelectionBuilder[ShardsAssigned, A] =
      _root_.caliban.client.SelectionBuilder.Field("pod", Obj(innerSelection))
    def shards: SelectionBuilder[ShardsAssigned, List[Int]]                                          =
      _root_.caliban.client.SelectionBuilder.Field("shards", ListOf(Scalar()))
  }

  type ShardsUnassigned
  object ShardsUnassigned {
    def pod[A](innerSelection: SelectionBuilder[PodAddress, A]): SelectionBuilder[ShardsUnassigned, A] =
      _root_.caliban.client.SelectionBuilder.Field("pod", Obj(innerSelection))
    def shards: SelectionBuilder[ShardsUnassigned, List[Int]]                                          =
      _root_.caliban.client.SelectionBuilder.Field("shards", ListOf(Scalar()))
  }

  final case class PodAddressInput(host: String, port: Int)
  object PodAddressInput {
    implicit val encoder: ArgEncoder[PodAddressInput] = new ArgEncoder[PodAddressInput] {
      override def encode(value: PodAddressInput): __Value =
        __ObjectValue(
          List(
            "host" -> implicitly[ArgEncoder[String]].encode(value.host),
            "port" -> implicitly[ArgEncoder[Int]].encode(value.port)
          )
        )
    }
  }
  type Queries = _root_.caliban.client.Operations.RootQuery
  object Queries         {
    def getAssignments[A](
      innerSelection: SelectionBuilder[Assignment, A]
    ): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, List[A]] =
      _root_.caliban.client.SelectionBuilder.Field("getAssignments", ListOf(Obj(innerSelection)))
  }

  type Mutations = _root_.caliban.client.Operations.RootMutation
  object Mutations {
    def register(address: PodAddressInput, version: String)(implicit
      encoder0: ArgEncoder[PodAddressInput],
      encoder1: ArgEncoder[String]
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, Option[Unit]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "register",
        OptionOf(Scalar()),
        arguments = List(
          Argument("address", address, "PodAddressInput!")(encoder0),
          Argument("version", version, "String!")(encoder1)
        )
      )
    def unregister(address: PodAddressInput, version: String)(implicit
      encoder0: ArgEncoder[PodAddressInput],
      encoder1: ArgEncoder[String]
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, Option[Unit]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "unregister",
        OptionOf(Scalar()),
        arguments = List(
          Argument("address", address, "PodAddressInput!")(encoder0),
          Argument("version", version, "String!")(encoder1)
        )
      )
    def notifyUnhealthyPod(podAddress: PodAddressInput)(implicit
      encoder0: ArgEncoder[PodAddressInput]
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, Unit] = _root_.caliban.client.SelectionBuilder
      .Field(
        "notifyUnhealthyPod",
        Scalar(),
        arguments = List(Argument("podAddress", podAddress, "PodAddressInput!")(encoder0))
      )
  }

  type Subscriptions = _root_.caliban.client.Operations.RootSubscription
  object Subscriptions {
    def events[A](
      onShardsAssigned: SelectionBuilder[ShardsAssigned, A],
      onShardsUnassigned: SelectionBuilder[ShardsUnassigned, A]
    ): SelectionBuilder[_root_.caliban.client.Operations.RootSubscription, A] = _root_.caliban.client.SelectionBuilder
      .Field(
        "events",
        ChoiceOf(Map("ShardsAssigned" -> Obj(onShardsAssigned), "ShardsUnassigned" -> Obj(onShardsUnassigned)))
      )
  }

}
