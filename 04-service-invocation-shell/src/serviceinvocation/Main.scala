package serviceinvocation

import dapr4s.*

// ── Impure shell ──────────────────────────────────────────────────────────────

@scala.caps.assumeSafe
object Codecs:
  given upickle.default.ReadWriter[GreetRequest]  = upickle.default.macroRW
  given upickle.default.ReadWriter[GreetResponse] = upickle.default.macroRW
  given upickle.default.ReadWriter[StatsResponse] = upickle.default.macroRW
  given upickle.default.ReadWriter[ServiceStats]  = upickle.default.macroRW

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

@main def callee(): Unit =
  val config = daprConfigFromEnv(defaultAppPort = 8084)
  println(s"=== 04 service-invocation: callee on port ${config.appServer.port} ===\n")
  DaprRuntime.serve(config):
    calleeApp()

@main def caller(): Unit =
  println("=== 04 service-invocation: caller ===\n")
  DaprRuntime.run(daprConfigFromEnv(defaultAppPort = 8084)):
    val result = callerApp()
    result.greetings.foreach(r => println(s"${r.greeting}  (from: ${r.from})"))
    println()
    println(s"Total requests: ${result.stats.totalRequests}")
    println(s"Languages seen: ${result.stats.languages.mkString(", ")}")
  println("\n[caller] done.")
