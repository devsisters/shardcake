package com.devsisters.shardcake

import sttp.model.Uri

case class Config(
  numberOfShards: Int,
  selfHost: String,
  shardingPort: Int,
  shardManagerUri: Uri,
  serverVersion: String
)
