package orders

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

@main def ordersServer(): Unit =
  val config = daprConfigFromEnv(defaultAppPort = 8088)
  println(s"=== 14 observability: OrderWorkflow server on port ${config.appServer.port} ===\n")
  Dapr(config).serve:
    ServerApp()
