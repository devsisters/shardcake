# Configuration

## Sharding Configuration

Here's the list of thing you can configure on the pod side:
- `role`: the role of the current pod (defaults to `Role.default`)
::: tip Pod Roles
A `Role` is a tag (a simple wrapper around a `String` name) attached to each pod. Pods with the same role share a pool of shards, while pods with different roles are managed independently by the Shard Manager (each role has its own set of shards, its own rebalancing, and its own assignment state).

This is useful when you want to dedicate certain pods to certain entity types (e.g. a role for "game" pods and a role for "matchmaking" pods), or to scale parts of your system independently.

If you don't need this, just leave the default value: all pods will share the `default` role.

Note: a pod can only have one role, and a pod can't change its role while it's registered. Trying to re-register an existing pod with a different role will be rejected by the Shard Manager.
:::
- `numberOfShards`: number of shards (for the pod's role)
::: tip How to choose the number of shards?
`numberOfShards` is used to calculate the shard ID from the entity ID. For that reason, it should be the same on all pods of a given role, and can not be changed while the app is running.

If this value is lower than the number of pods, some pods will have no shards (and host no entities), which is inefficient.
If this value is too low, a difference of 1 shard between 2 pods will be quite significant and introduce some unbalance between the number of entities hosted on each pod.
On the other hand, if there are too many shards, each pod will have a lot of shards, which will produce an unnecessary overhead.

A good rule of thumb is to set the number of shards to 10x the maximum number of pods that you expect to have.
:::
- `selfHost`: hostname or IP address of the current pod
- `shardingPort`: port used for pods to communicate together
- `shardManagerUri`: url of the Shard Manager GraphQL API
- `serverVersion`: version of the current pod
::: tip What's the version?
When performing a rolling update (upgrading all pods one by one without downtime), we want to avoid assigning shards to pods that will be stopped soon.
Ideally, we want to move each shard only once during the whole process.

This `serverVersion` allows the Shard Manager to know which pods are old and which are new. It will then pick the new pods to assign shards.
:::
- `entityMaxIdleTime`: time of inactivity (without receiving any message) after which an entity will be stopped
::: tip Termination Message
`registerEntity` takes an optional parameter called `terminationMessage`. This allows defining a message that will be sent to your entities before they are stopped (either by a rebalance or because of inactivity).

If no termination message is provided, the entity queues will simply be shutdown.
But if you want to ensure entities are stopped "cleanly" after processing their last message, define a termination message and stop the behavior yourself (by calling `ZIO.interrupt`) after receiving that message.

Termination messages must contain a promise which you need to complete to indicate that shutdown is complete. See the [example here](https://github.com/devsisters/shardcake/tree/series/3.x/examples/src/main/scala/example/complex).
:::
- `entityTerminationTimeout`: time we give to an entity to handle the termination message before interrupting it
- `sendTimeout`: timeout when calling `sendMessage`
- `refreshAssignmentsRetryInterval`: retry interval in case of failure getting shard assignments from storage
- `unhealthyPodReportInterval`: interval to report unhealthy pods to the Shard Manager (this exists to prevent calling the Shard Manager for each failed message)
- `simulateRemotePods`: disable optimizations when sending a message to an entity hosted on the local shards (this will force serialization of all messages)

## Shard Manager Configuration

Here's the list of thing you can configure on the Shard Manager side:
- `numberOfShards`: a function `Role => Int` returning the number of shards for a given role (see above)
::: tip Why a function?
Because each role has its own pool of shards, you can pick a different number of shards for each role.
If you only use the `default` role (or want the same number of shards everywhere), you can simply pass `_ => 300` (or any constant).
:::
- `apiPort`: port to expose the GraphQL API
- `rebalanceInterval`: interval for regular rebalancing of shards
- `rebalanceRetryInterval`: retry interval for rebalancing when some shards failed to be rebalanced
- `pingTimeout`: time to wait for a pod to respond to a ping request
- `persistRetryInterval`: retry interval for persistence of pods and shard assignments
- `persistRetryCount`: max retry count for persistence of pods and shard assignments
- `rebalanceRate`: max ratio of shards to rebalance in a single iteration
- `podHealthCheckInterval`: interval for checking pod health
::: tip Rebalance Rate
The rebalance rate is there to prevent too many shards being assigned immediately to new pods.
Instead of reaching a perfect spread right away, we can use several iterations to make sure new pods are able to handle the new shards. 
This is particularly useful if starting entities has a performance cost (e.g. loading state) and we don't want to start too many at once.

When a pod is leaving, its shards need to be immediately rebalanced so the ratio is not used in that case.
:::
