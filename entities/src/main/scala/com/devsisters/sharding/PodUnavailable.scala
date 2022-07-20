package com.devsisters.sharding

case class PodUnavailable(pod: PodAddress) extends Exception(s"Pod $pod is unavailable")
