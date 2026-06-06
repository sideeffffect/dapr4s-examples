package bindings

import dapr4s.*

// ── Impure shell ──────────────────────────────────────────────────────────────

private def upickleCodec[T: upickle.default.ReadWriter]: JsonCodec[T] = new JsonCodec[T]:
  def encode(value: T): String = upickle.default.write(value)
  def decode(json: String | Null): Either[JsonDecodeException, T] =
    if json == null then Left(JsonDecodeException("null input"))
    else
      try Right(upickle.default.read[T](json))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

private given upickle.default.ReadWriter[Post] = upickle.default.macroRW
private given upickle.default.ReadWriter[NewPost] = upickle.default.macroRW
private given upickle.default.ReadWriter[PostRef] = upickle.default.macroRW

private given JsonCodec[Post] = upickleCodec
private given JsonCodec[NewPost] = upickleCodec
private given JsonCodec[PostRef] = upickleCodec
private given JsonCodec[String] = upickleCodec

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

@main def bindingsApp(): Unit =
  val config = daprConfigFromEnv(defaultAppPort = 8093)
  println(s"=== 13 bindings: output (HTTP + Kafka) & input (Kafka) on port ${config.appServer.port} ===\n")
  Dapr(config).serve:
    BindingsExampleApp()
