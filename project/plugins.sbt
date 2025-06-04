addSbtPlugin("org.scalameta"      % "sbt-scalafmt"   % "2.4.6")
addSbtPlugin("com.github.sbt"     % "sbt-ci-release" % "1.11.1")
addSbtPlugin("com.thesamet"       % "sbt-protoc"     % "1.0.7")
addSbtPlugin("pl.project13.scala" % "sbt-jmh"        % "0.4.7")

libraryDependencies += "com.thesamet.scalapb"          %% "compilerplugin"   % "0.11.17"
libraryDependencies += "com.thesamet.scalapb.zio-grpc" %% "zio-grpc-codegen" % "0.6.3"
