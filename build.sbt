val scala213 = "2.13.8"
val scala3   = "3.1.3"
val allScala = Seq(scala213, scala3)

val zioVersion            = "2.0.5"
val zioGrpcVersion        = "0.6.0-test7"
val zioK8sVersion         = "2.0.0"
val zioCacheVersion       = "0.2.0"
val zioCatsInteropVersion = "3.3.0"
val sttpVersion           = "3.7.0"
val calibanVersion        = "2.0.0"
val redis4catsVersion     = "1.2.0"
val chillVersion          = "0.9.5"
val testContainersVersion = "0.40.9"

inThisBuild(
  List(
    scalaVersion           := scala213,
    crossScalaVersions     := allScala,
    organization           := "com.devsisters",
    homepage               := Some(url("https://devsisters.github.io/shardcake/")),
    licenses               := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    scmInfo                := Some(
      ScmInfo(
        url("https://github.com/devsisters/shardcake"),
        "scm:git:git@github.com:devsisters/shardcake.git"
      )
    ),
    developers             := List(
      Developer(
        "ghostdogpr",
        "Pierre Ricadat",
        "ghostdogpr@gmail.com",
        url("https://github.com/ghostdogpr")
      )
    ),
    sonatypeRepository     := "https://s01.oss.sonatype.org/service/local",
    sonatypeCredentialHost := "s01.oss.sonatype.org"
  )
)

name := "shardcake"
addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")

lazy val root = project
  .in(file("."))
  .settings(publish / skip := true)
  .settings(crossScalaVersions := Nil)
  .aggregate(
    core,
    manager,
    entities,
    healthK8s,
    storageRedis,
    serializationKryo,
    grpcProtocol,
    examples
  )

lazy val core = project
  .in(file("core"))
  .settings(name := "shardcake-core")
  .settings(commonSettings)
  .settings(
    libraryDependencies ++=
      Seq(
        "dev.zio" %% "zio"         % zioVersion,
        "dev.zio" %% "zio-streams" % zioVersion
      )
  )

lazy val manager = project
  .in(file("manager"))
  .settings(name := "shardcake-manager")
  .settings(commonSettings)
  .dependsOn(core)
  .settings(
    libraryDependencies ++=
      Seq(
        "com.github.ghostdogpr" %% "caliban"          % calibanVersion,
        "com.github.ghostdogpr" %% "caliban-zio-http" % calibanVersion
      )
  )

lazy val entities = project
  .in(file("entities"))
  .settings(name := "shardcake-entities")
  .settings(commonSettings)
  .dependsOn(core)
  .settings(
    libraryDependencies ++=
      Seq(
        "com.github.ghostdogpr"         %% "caliban-client"                % calibanVersion,
        "com.softwaremill.sttp.client3" %% "async-http-client-backend-zio" % sttpVersion
      )
  )

lazy val healthK8s = project
  .in(file("health-k8s"))
  .settings(name := "shardcake-health-k8s")
  .settings(commonSettings)
  .dependsOn(core)
  .settings(
    libraryDependencies ++=
      Seq(
        "com.coralogix"                 %% "zio-k8s-client"                % zioK8sVersion,
        "dev.zio"                       %% "zio-cache"                     % zioCacheVersion,
        "com.softwaremill.sttp.client3" %% "async-http-client-backend-zio" % sttpVersion,
        "com.softwaremill.sttp.client3" %% "slf4j-backend"                 % sttpVersion
      )
  )

lazy val storageRedis = project
  .in(file("storage-redis"))
  .settings(name := "shardcake-storage-redis")
  .settings(commonSettings)
  .dependsOn(core)
  .settings(
    libraryDependencies ++=
      Seq(
        "dev.profunktor" %% "redis4cats-effects" % redis4catsVersion,
        "dev.profunktor" %% "redis4cats-streams" % redis4catsVersion,
        "dev.zio"        %% "zio-interop-cats"   % zioCatsInteropVersion
      )
  )

lazy val serializationKryo = project
  .in(file("serialization-kryo"))
  .settings(name := "shardcake-serialization-kryo")
  .settings(commonSettings)
  .dependsOn(core)
  .settings(
    libraryDependencies ++=
      Seq(
        ("com.twitter" %% "chill" % chillVersion).cross(CrossVersion.for3Use2_13)
      )
  )

lazy val grpcProtocol = project
  .in(file("protocol-grpc"))
  .settings(name := "shardcake-protocol-grpc")
  .settings(commonSettings)
  .settings(protobuf: _*)
  .settings(
    Compile / PB.targets := Seq(
      scalapb.gen(grpc = true)          -> (Compile / sourceManaged).value,
      scalapb.zio_grpc.ZioCodeGenerator -> (Compile / sourceManaged).value
    )
  )
  .dependsOn(core, entities)
  .settings(
    libraryDependencies ++= Seq(
      "com.thesamet.scalapb"          %% "scalapb-runtime"      % scalapb.compiler.Version.scalapbVersion % "protobuf",
      "com.thesamet.scalapb"          %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
      "com.thesamet.scalapb.zio-grpc" %% "zio-grpc-core"        % zioGrpcVersion,
      "io.grpc"                        % "grpc-netty"           % scalapb.compiler.Version.grpcJavaVersion,
      "io.grpc"                        % "grpc-services"        % scalapb.compiler.Version.grpcJavaVersion
    )
  )

lazy val examples = project
  .in(file("examples"))
  .settings(name := "examples")
  .settings(publish / skip := true)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++=
      Seq(
        "dev.zio" %% "zio"         % zioVersion,
        "dev.zio" %% "zio-streams" % zioVersion
      )
  )
  .dependsOn(manager, storageRedis, grpcProtocol, serializationKryo)

lazy val protobuf = Seq(
  PB.protocVersion := "3.19.2"
) ++ Project.inConfig(Test)(sbtprotoc.ProtocPlugin.protobufConfigSettings)

lazy val commonSettings = Def.settings(
  resolvers ++= Resolver.sonatypeOssRepos("snapshots"),
  testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework")),
  libraryDependencies ++=
    Seq(
      "dev.zio"      %% "zio-test"                  % zioVersion            % Test,
      "dev.zio"      %% "zio-test-sbt"              % zioVersion            % Test,
      "com.dimafeng" %% "testcontainers-scala-core" % testContainersVersion % Test
    ),
  Test / fork    := true,
  scalacOptions ++= Seq(
    "-deprecation",
    "-encoding",
    "UTF-8",
    "-feature",
    "-language:higherKinds",
    "-language:existentials",
    "-unchecked",
    "-Xfatal-warnings",
    "-language:postfixOps"
  ) ++ (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, 12)) =>
      Seq(
        "-Xsource:2.13",
        "-Yno-adapted-args",
        "-Ypartial-unification",
        "-Ywarn-extra-implicit",
        "-Ywarn-inaccessible",
        "-Ywarn-infer-any",
        "-Ywarn-unused:-nowarn",
        "-Ywarn-nullary-override",
        "-Ywarn-nullary-unit",
        "-opt-inline-from:<source>",
        "-opt-warnings",
        "-opt:l:inline",
        "-explaintypes"
      )
    case Some((2, 13)) =>
      Seq(
        "-Xlint:-byname-implicit",
        "-explaintypes"
      )

    case Some((3, _)) =>
      Seq(
        "-explain-types",
        "-Ykind-projector"
      )
    case _            => Nil
  })
)
