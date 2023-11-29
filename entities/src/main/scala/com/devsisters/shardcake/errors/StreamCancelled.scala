package com.devsisters.shardcake.errors

/**
 * Exception indicating that a stream was interrupted.
 * It could be caused by a shard rebalance, by the entity inactivity or by the pod being unavailable.
 */
case object StreamCancelled extends Exception(s"Stream connection was canceled.")
