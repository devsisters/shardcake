package example

import com.devsisters.shardcake._
import com.devsisters.shardcake.interfaces.{ Pods, Storage }
import io.grpc.{ Metadata, Status }
import zio.test._
import zio.{ Config => _, _ }

object GrpcAuthExampleSpec extends ZIOSpecDefault {

  private val validAuthenticationKey = "validAuthenticationKey"

  private val authKey = Metadata.Key.of("authentication-key", Metadata.ASCII_STRING_MARSHALLER)

  private val config = ZLayer.succeed(Config.default.copy(simulateRemotePods = true))

  private def grpcConfigLayer(clientAuthKey: String): ULayer[GrpcConfig] =
    ZLayer.succeed(
      GrpcConfig.default.copy(
        clientInterceptors = Seq(
          ShardingClientInterceptor.headersUpdater(_.put(authKey, clientAuthKey))
        ),
        serverInterceptors = Seq(
          ShardingServerInterceptor.beforeEach { ctx =>
            val authenticated = Option(ctx.requestMetadata.get(authKey)).contains(validAuthenticationKey)
            ZIO.unless(authenticated)(ZIO.fail(Status.UNAUTHENTICATED.asException())).unit
          }
        )
      )
    )

  def spec: Spec[TestEnvironment with Scope, Any] =
    suite("GrpcAuthExampleSpec")(
      test("auth example for gRPC") {
        val podAddress = PodAddress("localhost", 54321)
        ZIO.scoped {
          for {
            _                 <- Sharding.registerScoped
            podsClient        <- ZIO.service[Pods]
            invalidPodsClient <- ZIO
                                   .service[Pods]
                                   .provide(
                                     grpcConfigLayer("invalid"),
                                     GrpcPods.live
                                   )
            validRequest      <- podsClient.ping(podAddress).exit
            invalidRequest    <- invalidPodsClient.ping(podAddress).exit
          } yield assertTrue(validRequest.isSuccess, invalidRequest.isFailure)
        }
      }
    ).provide(
      ShardManagerClient.local,
      Storage.memory,
      config,
      grpcConfigLayer(validAuthenticationKey),
      Sharding.live,
      GrpcPods.live,
      GrpcShardingService.live
    )
}
