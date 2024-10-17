package com.devsisters

import scala.collection.mutable

package object shardcake {
  type ShardId     = Int
  type EpochMillis = Long

  private[shardcake] def renderShardIds(ids: Iterable[ShardId]): String =
    ids
      .foldLeft(mutable.BitSet.empty)(_ += _)
      .mkString("[", ", ", "]")

}
