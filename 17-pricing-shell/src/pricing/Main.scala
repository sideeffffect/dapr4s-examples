package pricing

import dapr4s.*
import scala.scalajs.js

// ── Impure shell (Scala.js) — twin of the JVM 14-pricing shell ───────────────

private def upickleCodec[T: upickle.default.ReadWriter]: JsonCodec[T] = new JsonCodec[T]:
  def encode(value: T): String = upickle.default.write(value)
  def decode(json: String | Null): Either[JsonDecodeException, T] =
    if json == null then Left(JsonDecodeException("null input"))
    else
      try Right(upickle.default.read[T](json))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

@scala.caps.assumeSafe
object Codecs:
  given JsonCodec[QuoteRequest] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[PriceQuote] = upickleCodec(using upickle.default.macroRW)

import Codecs.given

private def envInt(name: String, default: Int): Int =
  val v = js.Dynamic.global.process.env.selectDynamic(name)
  if js.isUndefined(v) || v == null then default else v.toString.toIntOption.getOrElse(default)

private def daprConfigFromEnv(defaultAppPort: Int): DaprConfig =
  DaprConfig(
    sidecar = SidecarConfig(
      httpEndpoint = java.net.URI.create(s"http://localhost:${envInt("DAPR_HTTP_PORT", 3500)}"),
      grpcEndpoint = java.net.URI.create(s"http://localhost:${envInt("DAPR_GRPC_PORT", 50001)}"),
      grpcTlsInsecure = false,
    ),
    appServer = AppServerConfig(port = DaprPort(envInt("APP_PORT", defaultAppPort))),
  )

@main def pricingServer(): Unit =
  val config = daprConfigFromEnv(defaultAppPort = 8080)
  println(s"=== 17 observability (Scala.js): pricing service on port ${config.appServer.port} ===")
  js.async {
    Dapr(config).serve:
      PricingApp()
  }: Unit
