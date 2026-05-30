package workflows

import dapr4s.*
import scala.concurrent.duration.*

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
  given JsonCodec[OrderRequest] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[ReservationResult] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[PaymentResult] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[ShipmentResult] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[OrderResult] = upickleCodec(using upickle.default.macroRW)
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

@main def workflowServer(): Unit =
  val config = daprConfigFromEnv(defaultAppPort = 8087)
  println(s"=== 07 workflows: OrderProcessingWorkflow server on port ${config.appServer.port} ===\n")
  Dapr(config).serve:
    serverApp()

@main def workflowDriver(): Unit =
  println("=== 07 workflows: OrderProcessingWorkflow driver ===\n")
  Dapr(daprConfigFromEnv(defaultAppPort = 8087)).run:
    val results = driverApp(WorkflowName("OrderProcessingWorkflow"), timeout = 30.seconds)
    results.foreach: r =>
      if r.timedOut then println(s"\nOrder ${r.orderId}: TIMED OUT")
      else
        val msg = r.result.map(x => s"success=${x.success}, message='${x.message}'").getOrElse("(no output)")
        println(s"\nOrder ${r.orderId}: $msg")
  println("\n[workflow-driver] done.")
