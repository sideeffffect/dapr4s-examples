package hellopubsub

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
given JsonCodec[Message] = upickleCodec(using upickle.default.macroRW)

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

@main def subscriber(): Unit =
  val config = daprConfigFromEnv(defaultAppPort = 8083)
  println(s"=== 03 hello-pubsub: subscriber on port ${config.appServer.port} ===\n")
  Dapr(config).serve:
    SubscriberApp()

@main def publisher(): Unit =
  println("=== 03 hello-pubsub: publisher ===\n")
  Dapr(daprConfigFromEnv(defaultAppPort = 8083)).run:
    println("Publishing 5 messages to hello-topic...")
    PublisherApp()
  println("[publisher] done.")
