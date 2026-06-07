package scangateway

import dapr4s.*

// ── Impure shell ──────────────────────────────────────────────────────────────

private def upickleCodec[T: upickle.default.ReadWriter]: JsonCodec[T] = new JsonCodec[T]:
  def encode(value: T): String = upickle.default.write(value)
  def decode(json: String | Null): Either[JsonDecodeException, T] =
    if json == null then Left(JsonDecodeException("null input"))
    else
      try Right(upickle.default.read[T](json))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

@scala.caps.assumeSafe
object Codecs:
  given JsonCodec[ScanRequest] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[SubmitResponse] = upickleCodec(using upickle.default.macroRW)

import Codecs.given

private def daprConfigFromEnv(defaultAppPort: Int): DaprConfig =
  val appPort = sys.env.getOrElse("APP_PORT", defaultAppPort.toString).toInt
  val http = sys.env.getOrElse("DAPR_HTTP_PORT", "3500").toInt
  val grpc = sys.env.getOrElse("DAPR_GRPC_PORT", "50001").toInt
  DaprConfig(
    sidecar = SidecarConfig(
      httpEndpoint = java.net.URI.create(s"http://localhost:$http"),
      grpcEndpoint = java.net.URI.create(s"http://localhost:$grpc"),
      grpcTlsInsecure = false,
    ),
    appServer = AppServerConfig(port = DaprPort(appPort)),
  )

@main def scanGateway(): Unit =
  val config = daprConfigFromEnv(defaultAppPort = 8088)
  println(s"=== 08 scan-pipeline: gateway on port ${config.appServer.port} ===\n")
  Dapr(config).serve:
    GatewayApp()

@main def scanSeed(): Unit =
  println("=== 08 scan-pipeline: seeding ScanRequested ===\n")
  Dapr(daprConfigFromEnv(defaultAppPort = 8088)).run:
    val ids = runSeed()
    ids.foreach(id => println(s"published: $id"))
  println("\n[scan-seed] done.")
