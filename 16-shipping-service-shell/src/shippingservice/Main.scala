package shippingservice

import dapr4s.*
import scala.scalajs.js

// ── Impure shell (Scala.js) — twin of the JVM 13-shipping-service shell ──────

private def upickleCodec[T: upickle.default.ReadWriter]: JsonCodec[T] = new JsonCodec[T]:
  def encode(value: T): String = upickle.default.write(value)
  def decode(json: String | Null): Either[JsonDecodeException, T] =
    if json == null then Left(JsonDecodeException("null input"))
    else
      try Right(upickle.default.read[T](json))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

@scala.caps.assumeSafe
object Codecs:
  given JsonCodec[ShipRequest] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[ShipmentResult] = upickleCodec(using upickle.default.macroRW)

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

@main def shippingServer(): Unit =
  val config = daprConfigFromEnv(defaultAppPort = 8080)
  println(s"=== 16 order-fulfillment (Scala.js): shipping-service on port ${config.appServer.port} ===")
  js.async {
    Dapr(config).serve:
      ShippingApp()
  }: Unit
