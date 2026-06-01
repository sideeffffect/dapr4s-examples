package scanresults

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
  // ReadWriter (not JsonCodec) so macroRW can derive the nested List[Finding].
  given upickle.default.ReadWriter[Finding] = upickle.default.macroRW
  given JsonCodec[ScanResult] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[Dashboard] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[Unit] with
    def encode(value: Unit): String = "null"
    def decode(json: String | Null): Either[JsonDecodeException, Unit] = Right(())

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

@main def scanResults(): Unit =
  val config = daprConfigFromEnv(defaultAppPort = 8090)
  println(s"=== 08 scan-pipeline: results on port ${config.appServer.port} ===\n")
  Dapr(config).serve:
    resultsApp()
