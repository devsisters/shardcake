# Metrics

Shardcake exposes a few metrics to give you better visibility into the state of your cluster.
Those metrics are exposed via [ZIO Metrics](https://zio.dev/reference/observability/metrics/), which allows you to use the [backend of your choice](https://zio.dev/zio-metrics-connectors/).

Metrics that depend on a role are tagged with `role` (the role name) so you can break them down per role.
The shard-level Shard Manager gauges are additionally tagged with `pod_address`.

## Shard Manager Metrics
- `shardcake.pods` (gauge, tagged with `role`): Number of pods currently registered
- `shardcake.shards_assigned` (gauge, tagged with `role` and `pod_address`): Number of shards currently assigned to a pod
- `shardcake.shards_unassigned` (gauge, tagged with `role`): Number of shards currently not assigned to any pod
- `shardcake.rebalances` (counter, tagged with `role`): Number of rebalances that have occurred
- `shardcake.pod_health_checked` (counter, tagged with `pod_address`): Number of times the health of a pod has been checked

## Pod Metrics
- `shardcake.shards` (gauge, tagged with `role`): Number of shards currently assigned to the pod
- `shardcake.entities` (gauge, tagged with `role` and `type`): Number of entities currently running on the pod
- `shardcake.singletons` (gauge, tagged with `role` and `singleton_name`): Number of singletons currently running on the pod
