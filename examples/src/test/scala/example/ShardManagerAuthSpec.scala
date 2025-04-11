package example

import com.devsisters.shardcake.{ Config, ManagerConfig, Server, ShardManager, ShardManagerClient }
import com.devsisters.shardcake.interfaces.{ Pods, PodsHealth, Storage }
import sttp.client3.SttpBackend
import sttp.client3.asynchttpclient.zio.AsyncHttpClientZioBackend
import sttp.client3.httpclient.zio.ZioWebSocketsStreams
import zio.Clock.ClockLive
import zio.http.{ Header, Middleware }
import zio.test._
import zio.{ Config => _, _ }

object ShardManagerAuthSpec extends ZIOSpecDefault {

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

  def sttpBackendWithAuthTokenLayer(token: String): ZLayer[Scope, Throwable, SttpBackend[Task, ZioWebSocketsStreams]] =
    ZLayer {
      val authHeader = Header.Authorization.Bearer(token)
      AsyncHttpClientZioBackend.scoped(customizeRequest =
        builder => builder.addHeader(authHeader.headerName, authHeader.renderedValue)
      )
    }

  def spec: Spec[TestEnvironment, Any] =
    suite("ShardManagerAuthSpec")(
      test("auth example token validation") {
        ZIO.scoped {
          for {
            validClient     <- ZIO
                                 .service[ShardManagerClient]
                                 .provideSome[Config & Scope](
                                   sttpBackendWithAuthTokenLayer(validToken),
                                   ShardManagerClient.live
                                 )
            invalidClient   <- ZIO
                                 .service[ShardManagerClient]
                                 .provideSome[Config & Scope](
                                   sttpBackendWithAuthTokenLayer("invalid"),
                                   ShardManagerClient.live
                                 )
            validResponse   <- validClient.getAssignments.exit
            invalidResponse <- invalidClient.getAssignments.exit
          } yield assertTrue(validResponse.isSuccess) && assertTrue(invalidResponse.isFailure)
        }
      }
    ).provide(
      shardManagerServerLayer,
      ZLayer.succeed(Config.default),
      ZLayer.succeed(ManagerConfig.default)
    )
}
