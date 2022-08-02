# Getting Started

**Shardcake** is a **Scala open source library** that makes it easy to distribute entities across multiple servers and interact with those entities using their ID without knowing their actual location (this is also known as *location transparency*). 

## A simple use case

We are building a multiplayer game where **users** can join **guilds**.
We expect our game to be successful, so we need to be able to scale out (deploy it on multiple nodes to handle the load).

A guild is limited to 30 members.
Let's consider the case where 2 users try to join a guild at the exact same time, but our guild already had 29 members.

![naive diagram](/shardcake/usecase1.png)

If we implement this naively, 2 different nodes might receive our 2 requests to join the guild.
They will both check the current size of the guild, which is 29 and accept the new guild member. Now our guild has 31 members :scream:.

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