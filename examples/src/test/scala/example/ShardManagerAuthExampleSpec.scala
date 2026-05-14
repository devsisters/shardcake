package example

import com.devsisters.shardcake.{ Config, ManagerConfig, Role, Server, ShardManager, ShardManagerClient }
import com.devsisters.shardcake.interfaces.{ Pods, PodsHealth, Storage }
import sttp.client4.Backend
import sttp.client4.httpclient.zio._
import zio.Clock.ClockLive
import zio.http.{ Header, Middleware }
import zio.test._
import zio.{ Config => _, _ }
import java.net.http.HttpRequest

object ShardManagerAuthExampleSpec extends ZIOSpecDefault {

  val validToken = "validBearerToken"

  val shardManagerServerLayer: ZLayer[ManagerConfig, Throwable, Unit] =
    ZLayer.makeSome[ManagerConfig, Unit](
      ZLayer(
        Server
          .run(Middleware.bearerAuthZIO(secret => ZIO.succeed(secret.stringValue.equals(validToken))))
          .forkDaemon *> ClockLive.sleep(3 seconds).unit
      ),
      Storage.memory,
      ShardManager.live,
      Pods.noop,
      PodsHealth.noop
    )

  def sttpBackendWithAuthTokenLayer(token: String): ZLayer[Scope, Throwable, Backend[Task]] =
    ZLayer {
      val authHeader = Header.Authorization.Bearer(token)
      HttpClientZioBackend.scoped(customizeRequest = req => {
        val builder =
          HttpRequest
            .newBuilder(req.uri())
            .method(req.method(), req.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()))
        req.headers().map().forEach((k, vs) => vs.forEach(v => builder.header(k, v)))
        builder.header(authHeader.headerName, authHeader.renderedValue).build()
      })
    }

  def spec: Spec[TestEnvironment, Any] =
    suite("ShardManagerAuthSpec")(
      test("auth example for shard manager") {
        ZIO.scoped {
          for {
            validClient    <- ZIO
                                .service[ShardManagerClient]
                                .provideSome[Config & Scope](
                                  sttpBackendWithAuthTokenLayer(validToken),
                                  ShardManagerClient.live
                                )
            invalidClient  <- ZIO
                                .service[ShardManagerClient]
                                .provideSome[Config & Scope](
                                  sttpBackendWithAuthTokenLayer("invalid"),
                                  ShardManagerClient.live
                                )
            validRequest   <- validClient.getAssignments(Role.default).exit
            invalidRequest <- invalidClient.getAssignments(Role.default).exit
          } yield assertTrue(validRequest.isSuccess, invalidRequest.isFailure)
        }
      }
    ).provide(
      shardManagerServerLayer,
      ZLayer.succeed(Config.default),
      ZLayer.succeed(ManagerConfig.default)
    )
}
