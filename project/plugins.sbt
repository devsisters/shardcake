addSbtPlugin("org.scalameta"  % "sbt-scalafmt"   % "2.4.6")
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.5.10")
addSbtPlugin("com.thesamet"   % "sbt-protoc"     % "1.0.6")

libraryDependencies += "com.thesamet.scalapb"          %% "compilerplugin"   % "0.11.10"
libraryDependencies += "com.thesamet.scalapb.zio-grpc" %% "zio-grpc-codegen" % "0.6.0-test3"
