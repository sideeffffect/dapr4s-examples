package orderservice

import dapr4s.*
import scala.concurrent.duration.*

// ── Impure shell ──────────────────────────────────────────────────────────────
// Only the parts that safe mode genuinely rejects live here: the upickle codec
// derivations (upickle is not capture-aware, hence @assumeSafe) and the `@main`
// entry points (which do console I/O).  Everything else — the domain model, the
// activities, the saga workflow, ServerApp, and the workflow-client logic
// (processOrder / DriverApp) — is pure and lives in the non-shell module (see
// App.scala).  The activities no longer capture a InvokeCapability:
// WorkflowActivity.execute now receives a DaprCapability per call.
// ─────────────────────────────────────────────────────────────────────────────

private def upickleCodec[T: upickle.default.ReadWriter]: JsonCodec[T] = new JsonCodec[T]:
  def encode(value: T): String = upickle.default.write(value)
  def decode(json: String | Null): Either[JsonDecodeException, T] =
    if json == null then Left(JsonDecodeException("null input"))
    else
      try Right(upickle.default.read[T](json))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

@scala.caps.assumeSafe
object Codecs:
  given JsonCodec[OrderRequest] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[OrderResult] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[ReserveRequest] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[ReservationResult] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[ReleaseRequest] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[ChargeRequest] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[PaymentResult] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[RefundRequest] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[ShipRequest] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[ShipmentResult] = upickleCodec(using upickle.default.macroRW)
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

@main def orderServer(): Unit =
  val config = daprConfigFromEnv(defaultAppPort = 8091)
  println(s"=== 09 order-fulfillment: order-service (saga) on port ${config.appServer.port} ===\n")
  Dapr(config).serve:
    ServerApp(timeout = 30.seconds)

@main def orderDriver(): Unit =
  println("=== 09 order-fulfillment: driver ===\n")
  Dapr(daprConfigFromEnv(defaultAppPort = 8091)).run:
    val results = DriverApp(timeout = 30.seconds)
    results.foreach: r =>
      if r.timedOut then println(s"\nOrder ${r.orderId}: TIMED OUT")
      else
        val msg = r.result.map(x => s"success=${x.success}, message='${x.message}'").getOrElse("(no output)")
        println(s"\nOrder ${r.orderId}: $msg")
  println("\n[order-driver] done.")
