package serviceinvocation

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
  given JsonCodec[GreetRequest] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[GreetResponse] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[StatsResponse] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[ServiceStats] = upickleCodec(using upickle.default.macroRW)
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

@main def callee(): Unit =
  val config = daprConfigFromEnv(defaultAppPort = 8084)
  println(s"=== 04 service-invocation: callee on port ${config.appServer.port} ===\n")
  Dapr(config).serve:
    CalleeApp()

@main def caller(): Unit =
  println("=== 04 service-invocation: caller ===\n")
  Dapr(daprConfigFromEnv(defaultAppPort = 8084)).run:
    val result = CallerApp()
    result.greetings.foreach(r => println(s"${r.greeting}  (from: ${r.from})"))
    println()
    println(s"Total requests: ${result.stats.totalRequests}")
    println(s"Languages seen: ${result.stats.languages.mkString(", ")}")
  println("\n[caller] done.")
