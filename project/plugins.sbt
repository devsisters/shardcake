addSbtPlugin("org.scalameta"  % "sbt-scalafmt"   % "2.4.6")
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.5.10")
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype"   % "3.9.13")
addSbtPlugin("com.thesamet"   % "sbt-protoc"     % "1.0.6")

resolvers ++= Resolver.sonatypeOssRepos("snapshots")

libraryDependencies += "com.thesamet.scalapb"          %% "compilerplugin"   % "0.11.10"
libraryDependencies += "com.thesamet.scalapb.zio-grpc" %% "zio-grpc-codegen" % "0.6.1"
