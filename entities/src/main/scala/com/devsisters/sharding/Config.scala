package com.devsisters.sharding

import sttp.model.Uri

case class Config(
  numberOfShards: Int,
  selfHost: String,
  shardingPort: Int,
  shardManagerUri: Uri,
  serverVersion: String
)
