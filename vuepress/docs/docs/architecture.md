# Architecture

Make sure to read the [Getting Started](README.md#getting-started) and [Terminology](README.md#terminology) first.

## Main Concepts

![architecture diagram](/shardcake/arch2.png)

- The **Shard Manager** is a single node in charge of maintaining the pods <-> shards assignments.
  Only one Shard Manager should be alive at any given time.
- Pods **register** to the Shard Manager when they start and **unregister** when they stop.
- Pods **read the shard assignments** from the Storage layer (including updates) and cache them locally.
When the Shard Manager **assigns** or **unassigns** shards to/from pods, it notifies them directly.
- When a pod **sends a message** to a given entity, 
it checks which shard this entity belongs to and redirects the message to the pod in charge of that shard.
That pod will then start the entity behavior locally if it was not already started.
- The **Shard ID** assigned to an entity is calculated as follows:
`shardId = abs(entityId.hashCode) % numberOfShards + 1`. In other words, it is a stable number between 1 and the number of shards.
- When a pod is **unresponsive**, other pods will notify the Shard Manager, who will check with the Health API if the pod is still alive.
It will then unassign shards from this pod only if the pod is not alive
(as long as it’s alive, we can’t reassign its shards because it might cause an entity to be alive in 2 different pods).

::: tip No single point of failure
The Shard Manager is actually involved only when pods are added or removed.

The rest of the time, it does nothing since pods have a cached version of shard assignments and communicate between themselves directly.
That means they are able to work even if the Shard Manager is down.
:::

## Detailed flows and algorithms