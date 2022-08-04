# Getting Started

**Shardcake** is a **Scala open source library** that makes it easy to distribute entities across multiple servers and interact with those entities using their ID without knowing their actual location (this is also known as *location transparency*). 

Shardcake exposes a **purely functional API** and depends heavily on [ZIO](https://zio.dev). It is recommended to be familiar with ZIO to read this documentation.

## A simple use case

We are building a multiplayer game where **users** can join **guilds**.
We expect our game to be successful, so we need to be able to scale out (deploy it on multiple nodes to handle the load).

A guild is limited to 30 members.
Let's consider the case where 2 users try to join a guild at the exact same time, but our guild already had 29 members.

![naive diagram](/shardcake/usecase1.png)

If we implement this naively, 2 different nodes might receive our 2 requests to join the guild.
They will both check the current size of the guild at the same time, which will be 29 and they will both accept the new guild member. Now our guild has 31 members :scream:.

There are 2 common approaches to deal with this issue:
- **Global lock** approach: when checking the members of the guild, acquire a lock that is shared between the different game servers, and release it after saving the new member. 
That way, if 2 different game servers try to do it at the same time, the 2nd one will wait for the first one to finish.
- **Single writer** approach: instead of handling the requests on 2 game servers concurrently, redirect the 2 requests to a single entity that will handle them sequentially.

**Entity Sharding** is a way to implement the second approach. In this case our entities (here, our guilds) will be spread across our game servers so that
each entity exists in only one place at the time.

![single writer diagram](/shardcake/usecase2.png)

If our entity might be on any game server, how do we know where it is? This is the second characteristic of entity sharding, known as location transparency: we only need to know the entity ID.
Using the entity ID, the sharding system will be able to find on which server the entity is located.

**Shardcake** provides components to:
- automatically manage the assignments of entities to game servers
- send messages to your entities using their IDs

Once sharding is setup, it will let you write code like the following:

```scala
def joinGuild(guildId: GuildId): ZIO[Context, Throwable, GuildState] =
  for {
    userId     <- getCurrentUserFromContext
    guildState <- guild.send(guildId)(JoinGuild(userId, _))
  } yield guildState
```

## Terminology

Before we go further, let's define a few terms:
- An **entity** is a small message handler that can be addressed by ID. 
For example, `User A` or `Guild B` are entities. In this case we say that `User` and `Guild` are **entity types**.
- A **pod** is an application server that can host entities.
Entities usually run on multiple pods, but a single entity will only run on a single pod at the time. You will never have the same entity running on 2 different pods.
- A **shard** is a logical group of entities that will always be located on the same pod.
There might be millions of entities, so instead of keeping millions of entities-to-pods mappings, we group entities into shards and maintain reasonably-sized shards-to-pods mappings.

## Key components

Shardcake is composed of 2 main components:
- The **Shard Manager** is a independent component that needs a single instance running. It is in charge of assigning shards to pods.
- **Entities** will run on your application servers and can be sent messages.

There are 4 pluggable parts that can be implemented with the technology of your choice.
- The `Storage` trait defines where shard assignments will be stored. Shardcake provides an implementation using **Redis**.
- The `Pods` trait defines how to communicate with remote pods. Shardcake provides an implementation using **gRPC** as the protocol.
- The `Serialization` defines how to encode and decode messages. Shardcake provides an implementation using **Kryo**.
- The `PodsHealth` trait defines how to check if a pod is healthy or not. Shardcake provides an implementation using the **k8s API**.

![single writer diagram](/shardcake/arch.png)

## Example

### Shard Manager
The first thing we need to do is start the Shard Manager. This component is available by importing the `shardcake-manager` dependency, and requires implementations of `Storage`, `Pods` and `PodsHealth` to work.

To make it simpler and run our example without 3rd parties, we're going to run a "fake" `PodsHealth` API that always returns `true`, and in-memory `Storage`.
We still need a proper messaging protocol to communicate with pods, so we're going to use `shardcake-protocol-grpc`.
```
libraryDependencies += "com.devsisters" %% "shardcake-manager"       % "0.0.1"
libraryDependencies += "com.devsisters" %% "shardcake-protocol-grpc" % "0.0.1"
```
The Shard Manager exposes a small GraphQL API, which means we need to start a small webserver. This can be done by calling `Server.run` and providing all the required dependencies.
```scala
import com.devsisters.shardcake._
import com.devsisters.shardcake.interfaces._
import zio._

object ShardManagerApp extends ZIOAppDefault {
  def run: Task[Nothing] =
    Server.run.provide(
      ZLayer.succeed(ManagerConfig.default),
      ZLayer.succeed(GrpcConfig.default),
      PodsHealth.local, // just ping a pod to see if it's alive
      GrpcPods.live,    // use gRPC protocol
      Storage.memory,   // store data in memory
      ShardManager.live // shard manager logic
    )
}
```
That's it! Running this small app will start the Shard Manager and its API. It is now ready to receive registration from pods.

### Entity Behavior
We now need to define our **entity behavior**: what kind of messages can our entity receive, and what to do when it receives those messages. We will model our **Guild** example.

Let's start with defining the messages. We will have 2: one for joining a guild and one for leaving.
We create a `sealed trait` that will contain all the possible message types.
Any message that can be replied needs to contain a `Replier[A]` where `A` is the response type.

```scala
sealed trait GuildMessage

object GuildMessage {
  case class Join(userId: String, replier: Replier[Try[Set[String]]]) extends GuildMessage
  case class Leave(userId: String)                                    extends GuildMessage
}
```
We also need to define an **Entity Type**. This is done by extending `EntityType` with the message type as well as a unique String that will be used only for this type.
```scala
object Guild extends EntityType[GuildMessage]("guild")
```

The behavior itself is a function with the following signature:
```scala
def behavior(entityId: String, messages: Dequeue[Message]): RIO[Env, Nothing]
```
It takes an `entityId` and a `Dequeue[Message]` (a queue of `Message` that you can consume) and returns a `ZIO` that never ends (hence the return type `Nothing`).
That function is just supposed to consume `messages` forever.

Let's first define how to handle a single `GuildMessage`.

We have a state `Ref[Set[String]]` that will contain the users in our guild.
When we receive a `Join` request, we check if the guild is already full (here, we hardcode the max to 5) and fail if this is the case.
We then modify the state to add the new user, and use `replier.reply` to send a response to the sender (here, sending the full list of guild members wrapped in a `Try` to express errors).
```scala
def handleMessage(state: Ref[Set[String]], message: GuildMessage): RIO[Sharding, Unit] =
  message match {
    case GuildMessage.Join(userId, replier) =>
      state.get.flatMap(members =>
        if (members.size >= 5)
          replier.reply(Failure(new Exception("Guild is already full!")))
        else
          state.updateAndGet(_ + userId).flatMap { newMembers =>
            replier.reply(Success(newMembers))
          }
      )
    case GuildMessage.Leave(userId)         =>
      state.update(_ - userId)
  }
```

We are now ready to create our behavior, starting from an empty state when the entity is created:
```scala
def behavior(entityId: String, messages: Dequeue[GuildMessage]): RIO[Sharding, Nothing] =
  Ref
    .make(Set.empty[String])
    .flatMap(state => messages.take.flatMap(handleMessage(state, _)).forever)
```

### Run the application

The final step of creating our entity is to register it to the Sharding engine, which is done by calling `Sharding.registerEntity`, passing the entity type and the behavior.
As a result, we get a `Messenger[GuildMessage]` which we can now use to send messages to entities.
```scala
  val program =
    for {
      guild <- Sharding.registerEntity(Guild, behavior)
      _     <- guild.send("guild1")(Join("user1", _)).debug
      _     <- guild.send("guild1")(Join("user2", _)).debug
      _     <- guild.send("guild1")(Join("user3", _)).debug
      _     <- guild.send("guild1")(Join("user4", _)).debug
      _     <- guild.send("guild1")(Join("user5", _)).debug
      _     <- guild.send("guild1")(Join("user6", _)).debug
    } yield ()
```

To run all this, we need to initialize the Sharding engine by calling `Sharding.registerScoped` (which is the equivalent of calling `Sharding.register` when the program starts and `Sharding.unregister` when the program ends).
This will notify the Shard Manager that a new pod is now ready to run entities, and shards may be assigned to it.
Finally, we provide all required dependencies as we did for the Shard Manager.
```scala
def run: Task[Unit] =
  ZIO.scoped {
    Sharding.registerScoped *> program
  }.provide(
    ZLayer.succeed(Config.default),
    ZLayer.succeed(GrpcConfig.default),
    Serialization.javaSerialization, // use java serialization for messages
    Storage.memory,                  // store data in memory
    ShardManagerClient.liveWithSttp, // client to communicate with the Shard Manager
    GrpcPods.live,                   // use gRPC protocol
    GrpcShardingService.live,        // expose gRPC service
    Sharding.live,                   // sharding logic
  )
```

We can now run our program. It will successively send 6 `Join` messages to our guild and print the result:
```
Success(Set(user1))
Success(Set(user1, user2))
Success(Set(user1, user2, user3))
Success(Set(user1, user2, user3, user4))
Success(HashSet(user1, user5, user4, user2, user3))
Failure(java.lang.Exception: Guild is already full!)
```

### Where to go from there?
In this simple example, we only had a single pod so there was no real benefit from using sharding.
But we could run the exact same code on multiple pods, and the sharding engine would ensure that each guild is only running on a single pod.
In other words, all messages sent to `guild1` would then be handled by the same pod.

The only changes we would need to make would be:
- to use an actual `Storage` implementation for sharding (e.g. Redis)
- to save our guild state somewhere instead of in memory, so that we don't lose this state if the guild entity is moved from one pod to another