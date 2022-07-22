package com.devsisters.shardcake.errors

import com.devsisters.shardcake.PodAddress

/**
 * Exception indicating that a pod is not responsive.
 */
case class PodUnavailable(pod: PodAddress) extends Exception(s"Pod $pod is unavailable")
