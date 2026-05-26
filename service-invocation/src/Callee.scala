package serviceinvocation

import dapr4s.*

// ── Safe Scala guarantee ────────────────────────────────────────────────────
// Each InvocationRoute handler is a method that declares its capability
// requirements as `using` context parameters.  The Scala 3 type system
// ensures those capabilities are in scope when the handler lambda is formed
// and tracks the capture in the lambda's type.  The DaprApp stores all
// handlers as existential AnyRef under @assumeSafe — the safety contract is
// proved at construction, not at call time.
// ─────────────────────────────────────────────────────────────────────────────

// ── Domain ────────────────────────────────────────────────────────────────────

case class GreetRequest(name: String, language: String = "en")
case class GreetResponse(greeting: String, from: String)
case class StatsResponse(totalRequests: Long, languages: List[String])

@scala.caps.assumeSafe
object Domain:
  given upickle.default.ReadWriter[GreetRequest]  = upickle.default.macroRW
  given upickle.default.ReadWriter[GreetResponse] = upickle.default.macroRW
  given upickle.default.ReadWriter[StatsResponse] = upickle.default.macroRW

import Domain.given

val StatStore = StoreName("statestore")
val CountKey  = StateKey("greet-count")
val LangsKey  = StateKey("greet-languages")

// ── Handlers ─────────────────────────────────────────────────────────────────
// Pure business-logic methods.  The `using StateCapability` threads the
// capability without making it an explicit field — it cannot be captured
// beyond the scope where it's provided.

def greet(req: GreetRequest)(using StateCapability): GreetResponse =
  val greeting = req.language match
    case "en" => s"Hello, ${req.name}!"
    case "es" => s"¡Hola, ${req.name}!"
    case "fr" => s"Bonjour, ${req.name}!"
    case "de" => s"Hallo, ${req.name}!"
    case _    => s"Hi, ${req.name}!"

  // Update counters in state — all within the same capability scope.
  val prev  = StateCapability.get[Long](CountKey).getOrElse(0L)
  val langs = StateCapability.get[List[String]](LangsKey).getOrElse(Nil)
  StateCapability.save(CountKey, prev + 1)
  if !langs.contains(req.language) then
    StateCapability.save(LangsKey, langs :+ req.language)

  GreetResponse(greeting, from = "greeting-service")

def stats()(using StateCapability): StatsResponse =
  StatsResponse(
    totalRequests = StateCapability.get[Long](CountKey).getOrElse(0L),
    languages     = StateCapability.get[List[String]](LangsKey).getOrElse(Nil),
  )

// ── Callee app ────────────────────────────────────────────────────────────────

@main def callee(): Unit =
  val port   = sys.env.getOrElse("APP_PORT", "8084").toInt
  val config = DaprRuntimeConfig(appServer = AppServerConfig(port = DaprPort(port)))
  println(s"=== 04 service-invocation: callee on port $port ===\n")

  DaprRuntime.serve(config):
    DaprCapability.state(StatStore):
      DaprApp(invocations = List(
        InvocationRoute[GreetRequest, GreetResponse](MethodName("greet"))(greet),
        InvocationRoute[Unit, StatsResponse](MethodName("stats"))(_ => stats()),
      ))
