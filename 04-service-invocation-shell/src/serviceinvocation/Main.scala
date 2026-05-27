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

@main def callee(): Unit =
  val port   = sys.env.getOrElse("APP_PORT", "8084").toInt
  val config = DaprRuntimeConfig(appServer = AppServerConfig(port = DaprPort(port)))
  println(s"=== 04 service-invocation: callee on port $port ===\n")
  DaprRuntime.serve(config):
    calleeApp()

@main def caller(): Unit =
  println("=== 04 service-invocation: caller ===\n")
  DaprRuntime.run(DaprRuntimeConfig()):
    val result = callerApp()
    result.greetings.foreach(r => println(s"${r.greeting}  (from: ${r.from})"))
    println()
    println(s"Total requests: ${result.stats.totalRequests}")
    println(s"Languages seen: ${result.stats.languages.mkString(", ")}")
  println("\n[caller] done.")
