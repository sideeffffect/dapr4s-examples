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

@main def workflowServer(): Unit =
  val port   = sys.env.getOrElse("APP_PORT", "8087").toInt
  val config = DaprRuntimeConfig(appServer = AppServerConfig(port = DaprPort(port)))
  println(s"=== 07 workflows: OrderProcessingWorkflow server on port $port ===\n")
  DaprRuntime.serve(config):
    serverApp()

@main def workflowDriver(): Unit =
  println("=== 07 workflows: OrderProcessingWorkflow driver ===\n")
  DaprRuntime.run(DaprRuntimeConfig()):
    val results = driverApp(WorkflowName("OrderProcessingWorkflow"), timeout = 30.seconds)
    results.foreach: r =>
      if r.timedOut then
        println(s"\nOrder ${r.orderId}: TIMED OUT")
      else
        val msg = r.result.map(x => s"success=${x.success}, message='${x.message}'").getOrElse("(no output)")
        println(s"\nOrder ${r.orderId}: $msg")
  println("\n[workflow-driver] done.")
