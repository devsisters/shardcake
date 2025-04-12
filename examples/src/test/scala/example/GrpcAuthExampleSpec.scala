package example

import com.devsisters.shardcake._
import com.devsisters.shardcake.interfaces.{ Pods, Storage }
import io.grpc.{ Metadata, Status }
import scalapb.zio_grpc.{ ZClientInterceptor, ZTransform }
import zio.test._
import zio.{ Config => _, _ }

object GrpcAuthExampleSpec extends ZIOSpecDefault {

  private val validAuthenticationKey = "validAuthenticationKey"

  private val authKey = Metadata.Key.of("authentication-key", io.grpc.Metadata.ASCII_STRING_MARSHALLER)

  private val config = ZLayer.succeed(Config.default.copy(simulateRemotePods = true))

  private def grpcConfigLayer(clientAuthKey: String): ULayer[GrpcConfig] =
    ZLayer.succeed(
      GrpcConfig.default.copy(
        clientInterceptors = Seq(
          ZClientInterceptor.headersUpdater((_, _, md) => md.put(authKey, clientAuthKey).unit)
        ),
        serverInterceptors = Seq(
          ZTransform { requestContext =>
            for {
              authenticated <- requestContext.metadata.get(authKey).map(_.contains(validAuthenticationKey))
              _             <- ZIO.when(!authenticated)(ZIO.fail(Status.UNAUTHENTICATED.asException))
            } yield requestContext
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
      KryoSerialization.live,
      GrpcPods.live,
      GrpcShardingService.live
    )
}
