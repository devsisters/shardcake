package com.devsisters.shardcake

case class PodAddress(host: String, port: Int) {
  override def toString: String = s"$host:$port"
}

object PodAddress {
  def apply(s: String): Option[PodAddress] =
    s.split(':').toList match {
      case host :: port :: Nil => port.toIntOption.map(port => PodAddress(host, port))
      case _                   => None
    }
}
