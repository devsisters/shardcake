package com.devsisters.sharding.errors

import com.devsisters.sharding.PodAddress

case class PodUnavailable(pod: PodAddress) extends Exception(s"Pod $pod is unavailable")
