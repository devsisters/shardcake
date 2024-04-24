package com.devsisters.shardcake

import com.devsisters.shardcake.Messenger.MessengerTimeout
import com.devsisters.shardcake.Sharding.{ EntityState, ShardingRegistrationEvent }
import com.devsisters.shardcake.errors.{ EntityNotManagedByThisPod, PodUnavailable, SendTimeoutException }
import com.devsisters.shardcake.interfaces.Pods.BinaryMessage
import com.devsisters.shardcake.interfaces.{ Pods, Serialization, Storage }
import com.devsisters.shardcake.internal.{ EntityManager, ReplyChannel }
import zio.{ Config => _, _ }
import zio.stream.ZStream

import java.time.OffsetDateTime
import scala.util.Try

/**
 * A component that takes care of communicating with sharded entities.
 * See the companion object for layer creation and public methods.
 */
class Sharding private (
  address: PodAddress,
  config: Config,
  shardAssignments: Ref[Map[ShardId, PodAddress]],
  entityStates: Ref[Map[String, EntityState]],
  singletons: Ref.Synchronized[List[(String, UIO[Nothing], Option[Fiber[Nothing, Nothing]])]],
  replyChannels: Ref[Map[String, ReplyChannel[Nothing]]], // channel for each pending reply,
  lastUnhealthyNodeReported: Ref[OffsetDateTime],
  isShuttingDownRef: Ref[Boolean],
  shardManager: ShardManagerClient,
  pods: Pods,
  storage: Storage,
  serialization: Serialization,
  eventsHub: Hub[ShardingRegistrationEvent]
) { self =>
  private[shardcake] def getShardId(recipientType: RecipientType[_], entityId: String): ShardId =
    recipientType.getShardId(entityId, config.numberOfShards)

  val register: Task[Unit] =
    ZIO.logDebug(s"Registering pod $address to Shard Manager") *>
      isShuttingDownRef.set(false) *>
      shardManager.register(address)

  val unregister: UIO[Unit] =
    // ping the shard manager first to stop if it's not available
    shardManager.getAssignments.foldCauseZIO(
      ZIO.logWarningCause("Shard Manager not available. Can't unregister cleanly", _),
      _ =>
        ZIO.logDebug(s"Stopping local entities") *>
          isShuttingDownRef.set(true) *>
          entityStates.get.flatMap(
            ZIO.foreachDiscard(_) { case (name, entity) =>
              entity.entityManager.terminateAllEntities.forkDaemon // run in a daemon fiber to make sure it doesn't get interrupted
                .flatMap(_.join)
                .catchAllCause(ZIO.logErrorCause(s"Error during stop of entity $name", _))
            }
          ) *>
          ZIO.logDebug(s"Unregistering pod $address to Shard Manager") *>
          shardManager.unregister(address).catchAllCause(ZIO.logErrorCause("Error during unregister", _))
    )

  private def isSingletonNode: UIO[Boolean] =
    // Start singletons on the pod hosting shard 1.
    shardAssignments.get.map(_.get(1).contains(address))

  private def startSingletonsIfNeeded: UIO[Unit] =
    ZIO
      .whenZIO(isSingletonNode) {
        singletons.updateZIO { singletons =>
          ZIO.foreach(singletons) {
            case (name, run, None) =>
              ZIO.logDebug(s"Starting singleton $name") *>
                Metrics.singletons.tagged("singleton_name", name).increment *>
                run.forkDaemon.map(fiber => (name, run, Some(fiber)))
            case other             => ZIO.succeed(other)
          }
        }
      }
      .unit

  private def stopSingletonsIfNeeded: UIO[Unit] =
    ZIO
      .unlessZIO(isSingletonNode) {
        singletons.updateZIO { singletons =>
          ZIO.foreach(singletons) {
            case (name, run, Some(fiber)) =>
              ZIO.logDebug(s"Stopping singleton $name") *>
                Metrics.singletons.tagged("singleton_name", name).decrement *>
                fiber.interrupt.as((name, run, None))
            case other                    => ZIO.succeed(other)
          }
        }
      }
      .unit

  def registerSingleton[R](name: String, run: URIO[R, Nothing]): URIO[R, Unit] =
    ZIO.environment[R].flatMap(env => singletons.update(list => (name, run.provideEnvironment(env), None) :: list)) <*
      startSingletonsIfNeeded *>
      eventsHub.publish(ShardingRegistrationEvent.SingletonRegistered(name)).unit

  private[shardcake] def assign(shards: Set[ShardId]): UIO[Unit] =
    ZIO
      .unlessZIO(isShuttingDown) {
        shardAssignments.update(shards.foldLeft(_) { case (map, shard) => map.updated(shard, address) }) *>
          Metrics.shards.incrementBy(shards.size) *>
          startSingletonsIfNeeded *>
          ZIO.logDebug(s"Assigned shards: $shards")
      }
      .unit

  private[shardcake] def unassign(shards: Set[ShardId]): UIO[Unit] =
    shardAssignments.update(shards.foldLeft(_) { case (map, shard) =>
      if (map.get(shard).contains(address)) map - shard else map
    }) *>
      ZIO.logDebug(s"Unassigning shards: $shards") *>
      entityStates.get.flatMap(state =>
        ZIO.foreachDiscard(state.values)(
          _.entityManager.terminateEntitiesOnShards(shards) // this will return once all shards are terminated
        )
      ) *>
      Metrics.shards.decrementBy(shards.size) *>
      stopSingletonsIfNeeded *>
      ZIO.logDebug(s"Unassigned shards: $shards")

  private[shardcake] def isEntityOnLocalShards(recipientType: RecipientType[_], entityId: String): UIO[Boolean] =
    for {
      shards <- shardAssignments.get
      shardId = getShardId(recipientType, entityId)
      pod     = shards.get(shardId)
    } yield pod.contains(address)

  def getPods: UIO[Set[PodAddress]] =
    shardAssignments.get.map(_.values.toSet)

  private def updateAssignments(
    assignmentsOpt: Map[ShardId, Option[PodAddress]],
    fromShardManager: Boolean
  ): UIO[Unit] = {
    val assignments = assignmentsOpt.flatMap { case (k, v) => v.map(k -> _) }
    ZIO.logDebug("Received new shard assignments") *>
      Metrics.shards
        .set(assignmentsOpt.count { case (_, podOpt) => podOpt.contains(address) })
        .when(fromShardManager) *>
      (if (fromShardManager) shardAssignments.update(map => if (map.isEmpty) assignments else map)
       else
         shardAssignments.update(map =>
           // we keep self assignments (we don't override them with the new assignments
           // because only the Shard Manager is able to change assignments of the current node, via assign/unassign
           assignments.filter { case (_, pod) => pod != address } ++
             map.filter { case (_, pod) => pod == address }
         ))
  }

  private[shardcake] val refreshAssignments: ZIO[Scope, Nothing, Unit] =
    for {
      latch           <- Promise.make[Nothing, Unit]
      assignmentStream = ZStream.fromZIO(
                           // first, get the assignments from the shard manager directly
                           shardManager.getAssignments.map(_ -> true)
                         ) ++
                           // then, get assignments changes from Redis
                           storage.assignmentsStream.map(_ -> false)
      _               <- assignmentStream.mapZIO { case (assignmentsOpt, fromShardManager) =>
                           updateAssignments(assignmentsOpt, fromShardManager) *> latch.succeed(()).when(fromShardManager)
                         }.runDrain
                           .retry(Schedule.fixed(config.refreshAssignmentsRetryInterval))
                           .interruptible
                           .forkDaemon
                           .withFinalizer(_.interrupt)
      _               <- latch.await
    } yield ()

  private[shardcake] def isShuttingDown: UIO[Boolean] =
    isShuttingDownRef.get

  def sendToLocalEntitySingleReply(msg: BinaryMessage): Task[Option[Array[Byte]]] =
    for {
      replyChannel <- ReplyChannel.single[Any]
      _            <- sendToLocalEntity(msg, replyChannel)
      res          <- replyChannel.output
      bytes        <- ZIO.foreach(res)(serialization.encode)
    } yield bytes

  def sendToLocalEntityStreamingReply(msg: BinaryMessage): ZStream[Any, Throwable, Array[Byte]] =
    ZStream.unwrap {
      for {
        replyChannel <- ReplyChannel.stream[Any]
        _            <- sendToLocalEntity(msg, replyChannel)
      } yield replyChannel.output.mapChunksZIO(serialization.encodeChunk)
    }

  private def sendToLocalEntity(msg: BinaryMessage, replyChannel: ReplyChannel[Nothing]): Task[Unit] =
    entityStates.get.flatMap(_.get(msg.entityType) match {
      case Some(state) => state.processBinary(msg, replyChannel).unit
      case None        => ZIO.fail(new Exception(s"Entity type ${msg.entityType} was not registered."))
    })

  private[shardcake] def initReply(id: String, replyChannel: ReplyChannel[Nothing]): UIO[Unit] =
    replyChannels.update(_.updated(id, replyChannel)) <*
      replyChannel.await.ensuring(replyChannels.update(_ - id)).forkDaemon

  def reply[Reply](reply: Reply, replier: Replier[Reply]): UIO[Unit] =
    replyChannels
      .modify(repliers => (repliers.get(replier.id), repliers - replier.id))
      .flatMap(ZIO.foreachDiscard(_)(_.asInstanceOf[ReplyChannel[Reply]].replySingle(reply)))

  def replyStream[Reply](replies: ZStream[Any, Nothing, Reply], replier: StreamReplier[Reply]): UIO[Unit] =
    replyChannels
      .modify(repliers => (repliers.get(replier.id), repliers - replier.id))
      .flatMap(ZIO.foreachDiscard(_)(_.asInstanceOf[ReplyChannel[Reply]].replyStream(replies)))

  private def sendToPod[Msg, Res](
    recipientTypeName: String,
    entityId: String,
    msg: Msg,
    pod: PodAddress,
    replyId: Option[String],
    replyChannel: ReplyChannel[Res]
  ): Task[Unit] =
    if (config.simulateRemotePods && pod == address) {
      serialization
        .encode(msg)
        .flatMap(bytes => sendToLocalEntity(BinaryMessage(entityId, recipientTypeName, bytes, replyId), replyChannel))
    } else if (pod == address) {
      // if pod = self, shortcut and send directly without serialization
      entityStates.get.flatMap(
        _.get(recipientTypeName) match {
          case Some(state) =>
            state.entityManager.asInstanceOf[EntityManager[Msg]].send(entityId, msg, replyId, replyChannel)
          case None        =>
            ZIO.fail(new Exception(s"Entity type $recipientTypeName was not registered."))
        }
      )
    } else {
      serialization
        .encode(msg)
        .flatMap { bytes =>
          val errorHandling: Throwable => ZIO[Any, Nothing, Any] =
            ZIO
              .whenCase(_) { case PodUnavailable(pod) =>
                val notify = Clock.currentDateTime.flatMap(cdt =>
                  lastUnhealthyNodeReported
                    .updateAndGet(old =>
                      if (old.plusNanos(config.unhealthyPodReportInterval.toNanos) isBefore cdt) cdt
                      else old
                    )
                    .map(_ isEqual cdt)
                )
                ZIO.whenZIO(notify)(
                  (shardManager.notifyUnhealthyPod(pod) *>
                    // just in case we missed the update from the pubsub, refresh assignments
                    shardManager.getAssignments
                      .flatMap(updateAssignments(_, fromShardManager = true))).forkDaemon
                )
              }

          val binaryMessage = BinaryMessage(entityId, recipientTypeName, bytes, replyId)

          replyChannel match {
            case _: ReplyChannel.FromPromise[_] =>
              pods.sendMessage(pod, binaryMessage).tapError(errorHandling).flatMap {
                case Some(bytes) => serialization.decode[Res](bytes).flatMap(replyChannel.replySingle)
                case None        => replyChannel.end
              }
            case _: ReplyChannel.FromQueue[_]   =>
              replyChannel.replyStream(
                pods
                  .sendMessageStreaming(pod, binaryMessage)
                  .tapError(errorHandling)
                  .mapChunksZIO(serialization.decodeChunk[Res])
              )
          }
        }
    }

  def messenger[Msg](
    entityType: EntityType[Msg],
    sendTimeout: MessengerTimeout = MessengerTimeout.InheritConfigTimeout
  ): Messenger[Msg] =
    new Messenger[Msg] {
      private val timeout = sendTimeout match {
        case MessengerTimeout.NoTimeout            => None
        case MessengerTimeout.InheritConfigTimeout => Some(config.sendTimeout)
        case MessengerTimeout.Timeout(duration)    => Some(duration)
      }

      def sendDiscard(entityId: String)(msg: Msg): Task[Unit] = {
        val send = sendMessage(entityId, msg, None)
        timeout.fold(send.unit)(t => send.timeout(t).unit)
      }

      def send[Res](entityId: String)(msg: Replier[Res] => Msg): Task[Res] =
        Random.nextUUID.flatMap { uuid =>
          val body = msg(Replier(uuid.toString))
          val send = sendMessage[Res](entityId, body, Some(uuid.toString)).flatMap {
            case Some(value) => ZIO.succeed(value)
            case None        => ZIO.fail(new Exception(s"Send returned nothing, entityId=$entityId, body=$body"))
          }
          timeout.fold(send)(t => send.timeoutFail(SendTimeoutException(entityType, entityId, body))(t).interruptible)
        }

      def sendStream[Res](entityId: String)(msg: StreamReplier[Res] => Msg): Task[ZStream[Any, Throwable, Res]] =
        Random.nextUUID.flatMap { uuid =>
          sendMessageStreaming[Res](entityId, msg(StreamReplier(uuid.toString)), Some(uuid.toString))
        }

      private def sendMessage[Res](entityId: String, msg: Msg, replyId: Option[String]): Task[Option[Res]] =
        for {
          replyChannel <- ReplyChannel.single[Res]
          _            <- sendMessageGeneric(entityId, msg, replyId, replyChannel)
          res          <- replyChannel.output
        } yield res

      private def sendMessageStreaming[Res](
        entityId: String,
        msg: Msg,
        replyId: Option[String]
      ): Task[ZStream[Any, Throwable, Res]] =
        for {
          replyChannel <- ReplyChannel.stream[Res]
          _            <- sendMessageGeneric(entityId, msg, replyId, replyChannel)
        } yield replyChannel.output

      private def sendMessageGeneric[Res](
        entityId: String,
        msg: Msg,
        replyId: Option[String],
        replyChannel: ReplyChannel[Res]
      ): Task[Unit] = {
        val shardId             = getShardId(entityType, entityId)
        def trySend: Task[Unit] =
          for {
            shards <- shardAssignments.get
            pod     = shards.get(shardId)
            _      <- pod match {
                        case Some(pod) =>
                          sendToPod[Msg, Res](entityType.name, entityId, msg, pod, replyId, replyChannel).catchSome {
                            case _: EntityNotManagedByThisPod | _: PodUnavailable =>
                              Clock.sleep(200.millis) *> trySend
                          }.onError(replyChannel.fail)
                        case None      =>
                          // no shard assignment, retry
                          Clock.sleep(100.millis) *> trySend
                      }
          } yield ()

        trySend
      }
    }

  def broadcaster[Msg](
    topicType: TopicType[Msg],
    sendTimeout: MessengerTimeout = MessengerTimeout.InheritConfigTimeout
  ): Broadcaster[Msg] =
    new Broadcaster[Msg] {
      private val timeout = sendTimeout match {
        case MessengerTimeout.NoTimeout            => None
        case MessengerTimeout.InheritConfigTimeout => Some(config.sendTimeout)
        case MessengerTimeout.Timeout(duration)    => Some(duration)
      }

      def broadcastDiscard(topic: String)(msg: Msg): UIO[Unit] =
        sendMessage(topic, msg, None).unit

      def broadcast[Res](topic: String)(msg: Replier[Res] => Msg): UIO[Map[PodAddress, Try[Res]]] =
        Random.nextUUID.flatMap { uuid =>
          val body = msg(Replier(uuid.toString))
          sendMessage[Res](topic, body, Some(uuid.toString)).interruptible
        }

      private def sendMessage[Res](topic: String, msg: Msg, replyId: Option[String]): UIO[Map[PodAddress, Try[Res]]] =
        for {
          pods <- getPods
          res  <- ZIO
                    .foreachPar(pods.toList) { pod =>
                      def trySend: Task[Option[Res]] =
                        for {
                          replyChannel <- ReplyChannel.single[Res]
                          _            <- sendToPod(topicType.name, topic, msg, pod, replyId, replyChannel).catchSome {
                                            case _: PodUnavailable => Clock.sleep(200.millis) *> trySend
                                          }.onError(replyChannel.fail)
                          res          <- replyChannel.output
                        } yield res

                      val send = trySend.flatMap {
                        case Some(value) => ZIO.succeed(value)
                        case None        =>
                          if (replyId.isDefined) ZIO.fail(new Exception(s"Send returned nothing, topic=$topic"))
                          else ZIO.succeed(null.asInstanceOf[Res])
                      }
                      timeout
                        .fold(send)(t => send.timeoutFail(new Exception(s"Send timed out, topic=$topic"))(t))
                        .either
                        .map(pod -> _.toTry)
                    }
        } yield res.toMap
    }

  def registerEntity[R, Req: Tag](
    entityType: EntityType[Req],
    behavior: (String, Queue[Req]) => RIO[R, Nothing],
    terminateMessage: Promise[Nothing, Unit] => Option[Req] = (_: Promise[Nothing, Unit]) => None,
    entityMaxIdleTime: Option[Duration] = None
  ): URIO[Scope with R, Unit] = registerRecipient(entityType, behavior, terminateMessage, entityMaxIdleTime) *>
    eventsHub.publish(ShardingRegistrationEvent.EntityRegistered(entityType)).unit

  def registerTopic[R, Req: Tag](
    topicType: TopicType[Req],
    behavior: (String, Queue[Req]) => RIO[R, Nothing],
    terminateMessage: Promise[Nothing, Unit] => Option[Req] = (_: Promise[Nothing, Unit]) => None
  ): URIO[Scope with R, Unit] = registerRecipient(topicType, behavior, terminateMessage) *>
    eventsHub.publish(ShardingRegistrationEvent.TopicRegistered(topicType)).unit

  def getShardingRegistrationEvents: ZStream[Any, Nothing, ShardingRegistrationEvent] =
    ZStream.fromHub(eventsHub)

  def registerRecipient[R, Req: Tag](
    recipientType: RecipientType[Req],
    behavior: (String, Queue[Req]) => RIO[R, Nothing],
    terminateMessage: Promise[Nothing, Unit] => Option[Req] = (_: Promise[Nothing, Unit]) => None,
    entityMaxIdleTime: Option[Duration] = None
  ): URIO[Scope with R, Unit] =
    for {
      entityManager <- EntityManager.make(recipientType, behavior, terminateMessage, self, config, entityMaxIdleTime)
      processBinary  = (msg: BinaryMessage, replyChannel: ReplyChannel[Nothing]) =>
                         serialization
                           .decode[Req](msg.body)
                           .flatMap(entityManager.send(msg.entityId, _, msg.replyId, replyChannel))
                           .catchAllCause(replyChannel.fail)
      _             <- entityStates.update(_.updated(recipientType.name, EntityState(entityManager, processBinary)))
    } yield ()

  def terminateLocalEntity(entityType: EntityType[_], entityId: String): UIO[Unit] =
    entityStates.get.flatMap(_.get(entityType.name) match {
      case Some(state) => state.entityManager.terminateEntity(entityId)
      case None        => ZIO.unit
    })
}

object Sharding {

  sealed trait ShardingRegistrationEvent

  object ShardingRegistrationEvent {
    case class EntityRegistered(entityType: EntityType[_]) extends ShardingRegistrationEvent {
      override def toString: String = s"Registered entity ${entityType.name}"
    }
    case class SingletonRegistered(name: String)           extends ShardingRegistrationEvent {
      override def toString: String = s"Registered singleton $name"
    }
    case class TopicRegistered(topicType: TopicType[_])    extends ShardingRegistrationEvent {
      override def toString: String = s"Registered topic ${topicType.name}"
    }
  }

  private[shardcake] case class EntityState(
    entityManager: EntityManager[Nothing],
    processBinary: (BinaryMessage, ReplyChannel[Nothing]) => UIO[Unit]
  )

  /**
   * A layer that sets up sharding communication between pods.
   */
  val live: ZLayer[Pods with ShardManagerClient with Storage with Serialization with Config, Throwable, Sharding] =
    ZLayer.scoped {
      for {
        config                    <- ZIO.service[Config]
        pods                      <- ZIO.service[Pods]
        shardManager              <- ZIO.service[ShardManagerClient]
        storage                   <- ZIO.service[Storage]
        serialization             <- ZIO.service[Serialization]
        shardsCache               <- Ref.make(Map.empty[ShardId, PodAddress])
        entityStates              <- Ref.make[Map[String, EntityState]](Map())
        singletons                <- Ref.Synchronized
                                       .make[List[(String, UIO[Nothing], Option[Fiber[Nothing, Nothing]])]](Nil)
                                       .withFinalizer(
                                         _.get.flatMap(singletons =>
                                           ZIO.foreach(singletons) {
                                             case (_, _, Some(fiber)) => fiber.interrupt
                                             case _                   => ZIO.unit
                                           }
                                         )
                                       )
        replyChannels             <- Ref.make[Map[String, ReplyChannel[Nothing]]](Map())
        cdt                       <- Clock.currentDateTime
        lastUnhealthyNodeReported <- Ref.make(cdt)
        shuttingDown              <- Ref.make(false)
        eventsHub                 <- Hub.unbounded[ShardingRegistrationEvent]
        sharding                   = new Sharding(
                                       PodAddress(config.selfHost, config.shardingPort),
                                       config,
                                       shardsCache,
                                       entityStates,
                                       singletons,
                                       replyChannels,
                                       lastUnhealthyNodeReported,
                                       shuttingDown,
                                       shardManager,
                                       pods,
                                       storage,
                                       serialization,
                                       eventsHub
                                     )
        _                         <- sharding.getShardingRegistrationEvents.mapZIO(event => ZIO.logInfo(event.toString)).runDrain.forkDaemon
        _                         <- sharding.refreshAssignments
      } yield sharding
    }

  /**
   * Notify the shard manager that shards can now be assigned to this pod.
   */
  def register: RIO[Sharding, Unit] =
    ZIO.serviceWithZIO[Sharding](_.register)

  /**
   * Notify the shard manager that shards must be unassigned from this pod.
   */
  def unregister: URIO[Sharding, Unit] =
    ZIO.serviceWithZIO[Sharding](_.unregister)

  /**
   * Same as `register`, but will automatically call `unregister` when the `Scope` is terminated.
   */
  def registerScoped: RIO[Sharding with Scope, Unit] =
    Sharding.register.withFinalizer(_ => Sharding.unregister)

  /**
   * Start a computation that is guaranteed to run only on a single pod.
   * Each pod should call `registerSingleton` but only a single pod will actually run it at any given time.
   */
  def registerSingleton[R](name: String, run: URIO[R, Nothing]): URIO[Sharding with R, Unit] =
    ZIO.serviceWithZIO[Sharding](_.registerSingleton[R](name, run))

  /**
   * Register a new entity type, allowing pods to send messages to entities of this type.
   * It takes a `behavior` which is a function from an entity ID and a queue of messages to a ZIO computation that runs forever and consumes those messages.
   * You can use `ZIO.interrupt` from the behavior to stop it (it will be restarted the next time the entity receives a message).
   * If provided, the optional `terminateMessage` will be sent to the entity before it is stopped, allowing for cleanup logic.
   */
  def registerEntity[R, Req: Tag](
    entityType: EntityType[Req],
    behavior: (String, Queue[Req]) => RIO[R, Nothing],
    terminateMessage: Promise[Nothing, Unit] => Option[Req] = (_: Promise[Nothing, Unit]) => None,
    entityMaxIdleTime: Option[Duration] = None
  ): URIO[Sharding with Scope with R, Unit] =
    ZIO.serviceWithZIO[Sharding](_.registerEntity[R, Req](entityType, behavior, terminateMessage, entityMaxIdleTime))

  /**
   * Register a new topic type, allowing pods to broadcast messages to subscribers.
   * It takes a `behavior` which is a function from a topic and a queue of messages to a ZIO computation that runs forever and consumes those messages.
   * You can use `ZIO.interrupt` from the behavior to stop it (it will be restarted the next time the topic receives a message).
   * If provided, the optional `terminateMessage` will be sent to the topic before it is stopped, allowing for cleanup logic.
   */
  def registerTopic[R, Req: Tag](
    topicType: TopicType[Req],
    behavior: (String, Queue[Req]) => RIO[R, Nothing],
    terminateMessage: Promise[Nothing, Unit] => Option[Req] = (_: Promise[Nothing, Unit]) => None
  ): URIO[Sharding with Scope with R, Unit] =
    ZIO.serviceWithZIO[Sharding](_.registerTopic[R, Req](topicType, behavior, terminateMessage))

  /**
   * Get an object that allows sending messages to a given entity type.
   * You can provide a custom send timeout to override the one globally defined.
   */
  def messenger[Msg](
    entityType: EntityType[Msg],
    sendTimeout: MessengerTimeout = MessengerTimeout.InheritConfigTimeout
  ): URIO[Sharding, Messenger[Msg]] =
    ZIO.serviceWith[Sharding](_.messenger(entityType, sendTimeout))

  /**
   * Get an object that allows broadcasting messages to a given topic type.
   * You can provide a custom send timeout to override the one globally defined.
   */
  def broadcaster[Msg](
    topicType: TopicType[Msg],
    sendTimeout: MessengerTimeout = MessengerTimeout.InheritConfigTimeout
  ): URIO[Sharding, Broadcaster[Msg]] =
    ZIO.serviceWith[Sharding](_.broadcaster(topicType, sendTimeout))

  /**
   * Get the list of pods currently registered to the Shard Manager
   */
  def getPods: RIO[Sharding, Set[PodAddress]] =
    ZIO.serviceWithZIO[Sharding](_.getPods)

  /**
   * Terminate a given entity. If a termination message was provided, that message will be sent to the entity and
   * no new message will be enqueued after that. If no termination message was provided, the entity will be stopped immediately.
   * This method can only be used if the entity is hosted on the current pod (otherwise it will do nothing).
   * Typically, you would use this method from inside the entity behavior to stop itself.
   */
  def terminateLocalEntity(entityType: EntityType[_], entityId: String): URIO[Sharding, Unit] =
    ZIO.serviceWithZIO[Sharding](_.terminateLocalEntity(entityType, entityId))
}
