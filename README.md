# Shardcake

[![Release Artifacts][Badge-SonatypeReleases]][Link-SonatypeReleases]
[![Snapshot Artifacts][Badge-SonatypeSnapshots]][Link-SonatypeSnapshots]

[Link-SonatypeReleases]: https://s01.oss.sonatype.org/content/repositories/releases/com/devsisters/shardcake-core_2.13/ "Sonatype Releases"
[Badge-SonatypeReleases]: https://img.shields.io/nexus/r/https/s01.oss.sonatype.org/com.devsisters/shardcake-core_2.13.svg "Sonatype Releases"
[Link-SonatypeSnapshots]: https://s01.oss.sonatype.org/content/repositories/snapshots/com/devsisters/shardcake-core_2.13/ "Sonatype Snapshots"
[Badge-SonatypeSnapshots]: https://img.shields.io/nexus/s/https/s01.oss.sonatype.org/com.devsisters/shardcake-core_2.13.svg "Sonatype Snapshots"

**Shardcake** is a **Scala open source library** that makes it easy to distribute entities across multiple servers and interact with those entities using their ID without knowing their actual location (this is also known as *location transparency*). 

Shardcake exposes a **purely functional API** and depends heavily on [ZIO](https://zio.dev).

The [Documentation](https://devsisters.github.io/shardcake/) explains how to use Shardcake, in particular:
- how to [get started](https://devsisters.github.io/shardcake/docs/)
- the [architecture](https://devsisters.github.io/shardcake/docs/architecture.html) behind shardcake
- how to [configure](https://devsisters.github.io/shardcake/docs/config.html) it
- parts you can [customize](https://devsisters.github.io/shardcake/docs/customization.html)
