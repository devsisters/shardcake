package com.devsisters.shardcake.internal

import caliban.client.FieldBuilder._
import caliban.client._
import caliban.client.__Value._

private[shardcake] object GraphQLClient {

  type Assignment
  object Assignment {
    def shardId: SelectionBuilder[Assignment, Int]                                                             =
      _root_.caliban.client.SelectionBuilder.Field("shardId", Scalar())
    def pod[A](innerSelection: SelectionBuilder[PodAddress, A]): SelectionBuilder[Assignment, scala.Option[A]] =
      _root_.caliban.client.SelectionBuilder.Field("pod", OptionOf(Obj(innerSelection)))
  }

  type PodAddress
  object PodAddress {
    def host: SelectionBuilder[PodAddress, String] = _root_.caliban.client.SelectionBuilder.Field("host", Scalar())
    def port: SelectionBuilder[PodAddress, Int]    = _root_.caliban.client.SelectionBuilder.Field("port", Scalar())
  }

  type PodHealthChecked
  object PodHealthChecked {
    def pod[A](innerSelection: SelectionBuilder[PodAddress, A]): SelectionBuilder[PodHealthChecked, A] =
      _root_.caliban.client.SelectionBuilder.Field("pod", Obj(innerSelection))
  }

  type PodRegistered
  object PodRegistered {
    def pod[A](innerSelection: SelectionBuilder[PodAddress, A]): SelectionBuilder[PodRegistered, A] =
      _root_.caliban.client.SelectionBuilder.Field("pod", Obj(innerSelection))
    def role[A](innerSelection: SelectionBuilder[Role, A]): SelectionBuilder[PodRegistered, A]      =
      _root_.caliban.client.SelectionBuilder.Field("role", Obj(innerSelection))
  }

  type PodUnregistered
  object PodUnregistered {
    def pod[A](innerSelection: SelectionBuilder[PodAddress, A]): SelectionBuilder[PodUnregistered, A] =
      _root_.caliban.client.SelectionBuilder.Field("pod", Obj(innerSelection))
  }

  type Role
  object Role {
    def name: SelectionBuilder[Role, String] = _root_.caliban.client.SelectionBuilder.Field("name", Scalar())
  }

  type ShardsAssigned
  object ShardsAssigned {
    def pod[A](innerSelection: SelectionBuilder[PodAddress, A]): SelectionBuilder[ShardsAssigned, A] =
      _root_.caliban.client.SelectionBuilder.Field("pod", Obj(innerSelection))
    def role[A](innerSelection: SelectionBuilder[Role, A]): SelectionBuilder[ShardsAssigned, A]      =
      _root_.caliban.client.SelectionBuilder.Field("role", Obj(innerSelection))
    def shards: SelectionBuilder[ShardsAssigned, List[Int]]                                          =
      _root_.caliban.client.SelectionBuilder.Field("shards", ListOf(Scalar()))
  }

  type ShardsUnassigned
  object ShardsUnassigned {
    def pod[A](innerSelection: SelectionBuilder[PodAddress, A]): SelectionBuilder[ShardsUnassigned, A] =
      _root_.caliban.client.SelectionBuilder.Field("pod", Obj(innerSelection))
    def role[A](innerSelection: SelectionBuilder[Role, A]): SelectionBuilder[ShardsUnassigned, A]      =
      _root_.caliban.client.SelectionBuilder.Field("role", Obj(innerSelection))
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
  final case class RoleInput(name: String)
  object RoleInput       {
    implicit val encoder: ArgEncoder[RoleInput] = new ArgEncoder[RoleInput] {
      override def encode(value: RoleInput): __Value =
        __ObjectValue(List("name" -> implicitly[ArgEncoder[String]].encode(value.name)))
    }
  }
  type Queries = _root_.caliban.client.Operations.RootQuery
  object Queries         {
    def getAssignments[A](role: String)(
      innerSelection: SelectionBuilder[Assignment, A]
    )(implicit encoder0: ArgEncoder[String]): SelectionBuilder[_root_.caliban.client.Operations.RootQuery, List[A]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "getAssignments",
        ListOf(Obj(innerSelection)),
        arguments = List(Argument("role", role, "String!")(encoder0))
      )
  }

  type Mutations = _root_.caliban.client.Operations.RootMutation
  object Mutations {
    def register(address: PodAddressInput, version: String, role: RoleInput)(implicit
      encoder0: ArgEncoder[PodAddressInput],
      encoder1: ArgEncoder[String],
      encoder2: ArgEncoder[RoleInput]
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Unit]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "register",
        OptionOf(Scalar()),
        arguments = List(
          Argument("address", address, "PodAddressInput!")(encoder0),
          Argument("version", version, "String!")(encoder1),
          Argument("role", role, "RoleInput!")(encoder2)
        )
      )
    def unregister(address: PodAddressInput, version: String, role: RoleInput)(implicit
      encoder0: ArgEncoder[PodAddressInput],
      encoder1: ArgEncoder[String],
      encoder2: ArgEncoder[RoleInput]
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, scala.Option[Unit]] =
      _root_.caliban.client.SelectionBuilder.Field(
        "unregister",
        OptionOf(Scalar()),
        arguments = List(
          Argument("address", address, "PodAddressInput!")(encoder0),
          Argument("version", version, "String!")(encoder1),
          Argument("role", role, "RoleInput!")(encoder2)
        )
      )
    def notifyUnhealthyPod(podAddress: PodAddressInput)(implicit
      encoder0: ArgEncoder[PodAddressInput]
    ): SelectionBuilder[_root_.caliban.client.Operations.RootMutation, Unit] =
      _root_.caliban.client.SelectionBuilder.Field(
        "notifyUnhealthyPod",
        Scalar(),
        arguments = List(Argument("podAddress", podAddress, "PodAddressInput!")(encoder0))
      )
    def checkAllPodsHealth: SelectionBuilder[_root_.caliban.client.Operations.RootMutation, Unit] =
      _root_.caliban.client.SelectionBuilder.Field("checkAllPodsHealth", Scalar())
  }

  type Subscriptions = _root_.caliban.client.Operations.RootSubscription
  object Subscriptions {
    def events[A](
      onPodHealthChecked: SelectionBuilder[PodHealthChecked, A],
      onPodRegistered: SelectionBuilder[PodRegistered, A],
      onPodUnregistered: SelectionBuilder[PodUnregistered, A],
      onShardsAssigned: SelectionBuilder[ShardsAssigned, A],
      onShardsUnassigned: SelectionBuilder[ShardsUnassigned, A]
    ): SelectionBuilder[_root_.caliban.client.Operations.RootSubscription, A] =
      _root_.caliban.client.SelectionBuilder.Field(
        "events",
        ChoiceOf(
          Map(
            "PodHealthChecked" -> Obj(onPodHealthChecked),
            "PodRegistered"    -> Obj(onPodRegistered),
            "PodUnregistered"  -> Obj(onPodUnregistered),
            "ShardsAssigned"   -> Obj(onShardsAssigned),
            "ShardsUnassigned" -> Obj(onShardsUnassigned)
          )
        )
      )
  }

}
