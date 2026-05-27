package workflows

import dapr4s.*
import scala.concurrent.duration.*

// ── Impure shell ──────────────────────────────────────────────────────────────

@scala.caps.assumeSafe
object Codecs:
  given upickle.default.ReadWriter[OrderRequest]      = upickle.default.macroRW
  given upickle.default.ReadWriter[ReservationResult] = upickle.default.macroRW
  given upickle.default.ReadWriter[PaymentResult]     = upickle.default.macroRW
  given upickle.default.ReadWriter[ShipmentResult]    = upickle.default.macroRW
  given upickle.default.ReadWriter[OrderResult]       = upickle.default.macroRW

import Codecs.given

private def daprConfigFromEnv(defaultAppPort: Int): DaprRuntimeConfig =
  val appPort = sys.env.getOrElse("APP_PORT",      defaultAppPort.toString).toInt
  val http    = sys.env.getOrElse("DAPR_HTTP_PORT", "3500").toInt
  val grpc    = sys.env.getOrElse("DAPR_GRPC_PORT", "50001").toInt
  DaprRuntimeConfig(
    sidecar   = SidecarConfig(
      httpEndpoint    = java.net.URI.create(s"http://localhost:$http"),
      grpcEndpoint    = java.net.URI.create(s"http://localhost:$grpc"),
      grpcTlsInsecure = false,
    ),
    appServer = AppServerConfig(port = DaprPort(appPort)),
  )

@main def workflowServer(): Unit =
  val config = daprConfigFromEnv(defaultAppPort = 8087)
  println(s"=== 07 workflows: OrderProcessingWorkflow server on port ${config.appServer.port} ===\n")
  DaprRuntime.serve(config):
    serverApp()

@main def workflowDriver(): Unit =
  println("=== 07 workflows: OrderProcessingWorkflow driver ===\n")
  DaprRuntime.run(daprConfigFromEnv(defaultAppPort = 8087)):
    val results = driverApp(WorkflowName("OrderProcessingWorkflow"), timeout = 30.seconds)
    results.foreach: r =>
      if r.timedOut then
        println(s"\nOrder ${r.orderId}: TIMED OUT")
      else
        val msg = r.result.map(x => s"success=${x.success}, message='${x.message}'").getOrElse("(no output)")
        println(s"\nOrder ${r.orderId}: $msg")
  println("\n[workflow-driver] done.")
