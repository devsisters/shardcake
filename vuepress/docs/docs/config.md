# Configuration

## Sharding Configuration

Here's the list of thing you can configure on the pod side:
- `numberOfShards`: number of shards
::: tip How to choose the number of shards?
`numberOfShards` is used to calculate the shard ID from the entity ID. For that reason, it should be the same on all pods, and can not be changed while the app is running.

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
- `entityTerminationTimeout`: time we give to an entity to handle the termination message before interrupting it
- `sendTimeout`: timeout when calling `sendMessage`
- `refreshAssignmentsRetryInterval`: retry interval in case of failure getting shard assignments from storage
- `unhealthyPodReportInterval`: interval to report unhealthy pods to the Shard Manager (this exists to prevent calling the Shard Manager for each failed message)

## Shard Manager Configuration

Here's the list of thing you can configure on the Shard Manager side:
- `numberOfShards`: number of shards (see above)
- `apiPort`: port to expose the GraphQL API
- `rebalanceInterval`: interval for regular rebalancing of shards
- `rebalanceRetryInterval`: retry interval for rebalancing when some shards failed to be rebalanced
- `pingTimeout`: time to wait for a pod to respond to a ping request
- `persistRetryInterval`: retry interval for persistence of pods and shard assignments
- `persistRetryCount`: max retry count for persistence of pods and shard assignments