package com.devsisters.shardcake.errors

import com.devsisters.shardcake.PodAddress

case class PodUnavailable(pod: PodAddress) extends Exception(s"Pod $pod is unavailable")
