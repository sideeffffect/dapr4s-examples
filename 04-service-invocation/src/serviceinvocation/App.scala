package serviceinvocation

import dapr4s.*

case class GreetRequest(name: String, language: String = "en")
case class GreetResponse(greeting: String, from: String)
case class StatsResponse(totalRequests: Long, languages: List[String])
case class ServiceStats(count: Long, languages: List[String]) // internal state
case class CallerResult(greetings: List[GreetResponse], stats: StatsResponse)

val StatStore = StateStoreName("statestore")
val StatsKey = StateStoreKey("service-stats")

// ── Capture-checked pure module ───────────────────────────────────────────────
// All state is stored as a single ServiceStats object, so only one codec
// instance is needed for state operations.  InvocationRoute and caller
// operations need GreetRequest/GreetResponse/StatsResponse codecs; all are
// passed from the shell so macro derivation stays out of this module.
// ─────────────────────────────────────────────────────────────────────────────

// ── Callee ────────────────────────────────────────────────────────────────────

def greet(req: GreetRequest)(using StateCapability, JsonCodec[ServiceStats]): GreetResponse =
  val greeting = req.language match
    case "en" => s"Hello, ${req.name}!"
    case "es" => s"¡Hola, ${req.name}!"
    case "fr" => s"Bonjour, ${req.name}!"
    case "de" => s"Hallo, ${req.name}!"
    case _    => s"Hi, ${req.name}!"
  val current = StateCapability.get[ServiceStats](StatsKey).getOrElse(ServiceStats(0, Nil))
  val updated = current.copy(
    count = current.count + 1,
    languages =
      if current.languages.contains(req.language) then current.languages
      else current.languages :+ req.language,
  )
  StateCapability.save(StatsKey, updated)
  GreetResponse(greeting, from = "greeting-service")

def stats()(using StateCapability, JsonCodec[ServiceStats]): StatsResponse =
  val s = StateCapability.get[ServiceStats](StatsKey).getOrElse(ServiceStats(0, Nil))
  StatsResponse(s.count, s.languages)

object CalleeApp:
  def apply()(using
      DaprCapability,
      JsonCodec[ServiceStats],
      JsonCodec[GreetRequest],
      JsonCodec[GreetResponse],
      JsonCodec[StatsResponse],
      JsonCodec[Unit],
  ): DaprApp =
    DaprCapability.state(StatStore):
      DaprApp(invocations =
        List(
          InvocationRoute[GreetRequest, GreetResponse](InvocationMethodName("greet"))(greet),
          InvocationRoute[Unit, StatsResponse](InvocationMethodName("stats"))(_ => stats()),
        ),
      )

// ── Caller ────────────────────────────────────────────────────────────────────

// Remote calls are addressed by AppId; `invoke[In](appId, method, body)[Out]` ties both
// the request and response types to the wire. The no-body overload (used for `stats`)
// matches the callee's `InvocationRoute[Unit, StatsResponse]`.
object CallerApp:
  def apply()(using
      DaprCapability,
      JsonCodec[GreetRequest],
      JsonCodec[GreetResponse],
      JsonCodec[StatsResponse],
  ): CallerResult =
    DaprCapability.invoker:
      val target = AppId("greeting-service")
      val requests = List(
        GreetRequest("Alice", "en"),
        GreetRequest("Bob", "es"),
        GreetRequest("Carol", "fr"),
        GreetRequest("Dave", "de"),
        GreetRequest("Eve", "jp"),
      )
      val greetings = requests.map: req =>
        ServiceInvocationCapability.invoke(target, InvocationMethodName("greet"), req)[GreetResponse]
      val s = ServiceInvocationCapability.invoke[StatsResponse](target, InvocationMethodName("stats"))
      CallerResult(greetings, s)
