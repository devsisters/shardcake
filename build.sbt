val scala3 = "3.3.7"

val zioVersion            = "2.1.24"
val proteusVersion        = "0.4.1"
val grpcNettyVersion      = "1.71.0"
val zioK8sVersion         = "3.2.0"
val zioK8sSttpVersion     = "3.11.0"
val zioCacheVersion       = "0.2.4"
val zioCatsInteropVersion = "23.1.0.5"
val zioJsonVersion        = "0.7.39"
val sttpVersion           = "4.0.13"
val calibanVersion        = "3.0.0"
val redis4catsVersion     = "2.0.1"
val redissonVersion       = "3.45.1"
val scalaKryoVersion      = "1.4.0"
val testContainersVersion = "0.44.1"
val scalaCompatVersion    = "2.13.0"

inThisBuild(
  List(
    scalaVersion := scala3,
    organization := "com.devsisters",
    homepage     := Some(url("https://devsisters.github.io/shardcake/")),
    licenses     := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    scmInfo      := Some(
      ScmInfo(
        url("https://github.com/devsisters/shardcake"),
        "scm:git:git@github.com:devsisters/shardcake.git"
      )
    ),
    developers   := List(
      Developer(
        "ghostdogpr",
        "Pierre Ricadat",
        "ghostdogpr@gmail.com",
        url("https://github.com/ghostdogpr")
      )
    )
  )
)

name := "shardcake"
addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck grpcProtocol/checkProto")

lazy val root = project
  .in(file("."))
  .settings(publish / skip := true)
  .aggregate(
    core,
    manager,
    entities,
    healthK8s,
    storageRedis,
    storageRedisson,
    serializationKryo,
    grpcProtocol,
    examples,
    benchmarks
  )

lazy val core = project
  .in(file("core"))
  .settings(name := "shardcake-core")
  .settings(commonSettings)
  .settings(
    libraryDependencies ++=
      Seq(
        "dev.zio"                %% "zio"                     % zioVersion,
        "dev.zio"                %% "zio-streams"             % zioVersion,
        "dev.zio"                %% "zio-json"                % zioJsonVersion,
        "org.scala-lang.modules" %% "scala-collection-compat" % scalaCompatVersion
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
        "com.github.ghostdogpr" %% "caliban-quick" % calibanVersion
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
        "com.github.ghostdogpr"         %% "caliban-client" % calibanVersion,
        "com.softwaremill.sttp.client4" %% "zio"            % sttpVersion
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
        "com.coralogix"                 %% "zio-k8s-client" % zioK8sVersion,
        "dev.zio"                       %% "zio-cache"      % zioCacheVersion,
        "com.softwaremill.sttp.client3" %% "zio"            % zioK8sSttpVersion,
        "com.softwaremill.sttp.client3" %% "slf4j-backend"  % zioK8sSttpVersion
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

lazy val storageRedisson = project
  .in(file("storage-redisson"))
  .settings(name := "shardcake-storage-redisson")
  .settings(commonSettings)
  .dependsOn(core)
  .settings(
    libraryDependencies ++=
      Seq(
        "org.redisson" % "redisson" % redissonVersion
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
        "io.altoo" %% "scala-kryo-serialization" % scalaKryoVersion
      )
  )

lazy val generateProto = taskKey[Unit]("Regenerate sharding.proto from the Scala protocol definition.")
lazy val checkProto    = taskKey[Unit]("Fail if sharding.proto is out of sync with the Scala protocol definition.")

lazy val grpcProtocol = project
  .in(file("protocol-grpc"))
  .settings(name := "shardcake-protocol-grpc")
  .settings(commonSettings)
  .dependsOn(core, entities)
  .settings(
    libraryDependencies ++= Seq(
      "com.github.ghostdogpr" %% "proteus-grpc"     % proteusVersion,
      "com.github.ghostdogpr" %% "proteus-grpc-zio" % proteusVersion,
      "io.grpc"                % "grpc-netty"       % grpcNettyVersion,
      "io.grpc"                % "grpc-services"    % grpcNettyVersion
    ),
    generateProto := {
      val cp     = (Compile / fullClasspath).value
      val log    = streams.value.log
      val output = (Compile / sourceDirectory).value / "protobuf"
      runner.value
        .run(
          "com.devsisters.shardcake.protocol.GenerateProto",
          cp.files,
          Seq(output.getAbsolutePath),
          log
        )
        .get
      log.info(s"Regenerated $output/sharding.proto")
    },
    checkProto    := {
      val log   = streams.value.log
      val proto = (Compile / sourceDirectory).value / "protobuf" / "sharding.proto"
      val _     = generateProto.value
      import scala.sys.process._
      val diff  = s"git diff --exit-code -- ${proto.getAbsolutePath}".!
      if (diff != 0) {
        sys.error(
          "sharding.proto is out of sync with the Scala protocol definition. Run `sbt grpcProtocol/generateProto` and commit the result."
        )
      } else log.info("sharding.proto is in sync.")
    }
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

lazy val benchmarks = project
  .in(file("benchmarks"))
  .settings(name := "benchmarks")
  .settings(publish / skip := true)
  .settings(commonSettings)
  .enablePlugins(JmhPlugin)
  .dependsOn(grpcProtocol, serializationKryo)

lazy val commonSettings = Def.settings(
  testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework")),
  libraryDependencies ++=
    Seq(
      "dev.zio"      %% "zio-test"                  % zioVersion            % Test,
      "dev.zio"      %% "zio-test-sbt"              % zioVersion            % Test,
      "com.dimafeng" %% "testcontainers-scala-core" % testContainersVersion % Test
    ),
  Test / fork    := true,
  Test / javaOptions ++= Seq(
    // Kryo requires this with the recent versions of Java
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
    "--add-opens=java.sql/java.sql=ALL-UNNAMED"
  ),
  scalacOptions ++= Seq(
    "-deprecation",
    "-encoding",
    "UTF-8",
    "-feature",
    "-language:higherKinds",
    "-language:existentials",
    "-unchecked",
    "-Xfatal-warnings",
    "-language:postfixOps",
    "-explain-types",
    "-Ykind-projector"
  )
)
