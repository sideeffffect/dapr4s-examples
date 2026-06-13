package orders

import dapr4s.*
import scala.scalajs.js

// ── Impure shell (Scala.js) — twin of the JVM 14-orders shell ────────────────
// The OrderWorkflow (workflow + service invocation + pub/sub) is shared verbatim
// (App.scala); only the `js.async` entry point and the env-driven config differ.

private def upickleCodec[T: upickle.default.ReadWriter]: JsonCodec[T] = new JsonCodec[T]:
  def encode(value: T): String = upickle.default.write(value)
  def decode(json: String | Null): Either[JsonDecodeException, T] =
    if json == null then Left(JsonDecodeException("null input"))
    else
      try Right(upickle.default.read[T](json))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

@scala.caps.assumeSafe
object Codecs:
  import upickle.default.{ReadWriter, macroRW}
  // upickle ReadWriters first, so macroRW for the nested ChargeInput can find the
  // ReadWriters for OrderRequest and PriceQuote (declare those before ChargeInput).
  private given ReadWriter[OrderRequest] = macroRW
  private given ReadWriter[ReservationResult] = macroRW
  private given ReadWriter[QuoteRequest] = macroRW
  private given ReadWriter[PriceQuote] = macroRW
  private given ReadWriter[ChargeInput] = macroRW
  private given ReadWriter[PaymentResult] = macroRW
  private given ReadWriter[ShipmentResult] = macroRW
  private given ReadWriter[OrderEvent] = macroRW
  private given ReadWriter[OrderResult] = macroRW

  given JsonCodec[OrderRequest] = upickleCodec
  given JsonCodec[ReservationResult] = upickleCodec
  given JsonCodec[QuoteRequest] = upickleCodec
  given JsonCodec[PriceQuote] = upickleCodec
  given JsonCodec[ChargeInput] = upickleCodec
  given JsonCodec[PaymentResult] = upickleCodec
  given JsonCodec[ShipmentResult] = upickleCodec
  given JsonCodec[OrderEvent] = upickleCodec
  given JsonCodec[OrderResult] = upickleCodec
  given JsonCodec[Unit] with
    def encode(value: Unit): String = "null"
    def decode(json: String | Null): Either[JsonDecodeException, Unit] = Right(())

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

@main def ordersServer(): Unit =
  val config = daprConfigFromEnv(defaultAppPort = 8080)
  println(s"=== 17 observability (Scala.js): OrderWorkflow server on port ${config.appServer.port} ===")
  js.async {
    Dapr(config).serve:
      ServerApp()
  }: Unit
