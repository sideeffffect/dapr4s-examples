package serviceinvocation

import dapr4s.*
import dapr4s.invoke.*
import dapr4s.state.*

case class GreetRequest(name: String, language: String = "en")
case class GreetResponse(greeting: String, from: String)
case class StatsResponse(totalRequests: Long, languages: List[String])
case class ServiceStats(count: Long, languages: List[String]) // internal state
case class CallerResult(
    greetings: List[GreetResponse],
    stats: StatsResponse,
    unknownHttpStatus: Int, // invokeHttp: the status of a non-2xx answer comes back as a value
    unknownTypedStatus: Option[Int], // typed invoke: the same answer is thrown as InvokeHttpException
)

val StatStore = StateStoreName("statestore")
val StatsKey = StateStoreKey("service-stats")

// ── Capture-checked pure module ───────────────────────────────────────────────
// All state is stored as a single ServiceStats object, so only one codec
// instance is needed for state operations.  InvokeRoute and caller
// operations need GreetRequest/GreetResponse/StatsResponse codecs; all are
// passed from the shell so macro derivation stays out of this module.
// ─────────────────────────────────────────────────────────────────────────────

// ── Callee ────────────────────────────────────────────────────────────────────

def greet(req: GreetRequest)(using StateCapability.Instance, JsonCodec[ServiceStats]): GreetResponse =
  val greeting = req.language match
    case "en" => s"Hello, ${req.name}!"
    case "es" => s"¡Hola, ${req.name}!"
    case "fr" => s"Bonjour, ${req.name}!"
    case "de" => s"Hallo, ${req.name}!"
    case _    => s"Hi, ${req.name}!"
  val current = StateCapability.Instance.get[ServiceStats](StatsKey).getOrElse(ServiceStats(0, Nil))
  val updated = current.copy(
    count = current.count + 1,
    languages =
      if current.languages.contains(req.language) then current.languages
      else current.languages :+ req.language,
  )
  StateCapability.Instance.save(StatsKey, updated)
  GreetResponse(greeting, from = "greeting-service")

// Read-only handler: it only needs the ReadOnly tier, so it cannot write state.
def stats()(using StateCapability.Instance.ReadOnly, JsonCodec[ServiceStats]): StatsResponse =
  val s = StateCapability.Instance.ReadOnly.get[ServiceStats](StatsKey).getOrElse(ServiceStats(0, Nil))
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
      DaprApp(invokeRoutes =
        List(
          InvokeRoute[GreetRequest, GreetResponse](InvokeMethodName("greet"))(greet),
          InvokeRoute[Unit, StatsResponse](InvokeMethodName("stats"))(_ => stats()),
        ),
      )

// ── Caller ────────────────────────────────────────────────────────────────────

// Remote calls are addressed by AppId; `invoke[In](appId, method, body)[Out]` ties both
// the request and response types to the wire. The no-body overload (used for `stats`)
// matches the callee's `InvokeRoute[Unit, StatsResponse]`. A call to a route the callee does
// not serve shows how a non-2xx answer surfaces: as a value from `invokeHttp`, as an
// `InvokeHttpException` from the typed `invoke`.
object CallerApp:
  def apply()(using
      DaprCapability,
      JsonCodec[GreetRequest],
      JsonCodec[GreetResponse],
      JsonCodec[StatsResponse],
  ): CallerResult =
    DaprCapability.invoke:
      val target = AppId("greeting-service")
      val requests = List(
        GreetRequest("Alice", "en"),
        GreetRequest("Bob", "es"),
        GreetRequest("Carol", "fr"),
        GreetRequest("Dave", "de"),
        GreetRequest("Eve", "jp"),
      )
      val greetings = requests.map: req =>
        InvokeCapability.invoke(target, InvokeMethodName("greet"), req)[GreetResponse]
      val s = InvokeCapability.invoke[StatsResponse](target, InvokeMethodName("stats"))

      // The callee serves no "no-such-method" route, so it answers 404.
      val unknown = InvokeMethodName("no-such-method")
      // invokeHttp never throws on status: status, headers and body come back as an InvokeResponse.
      val raw = InvokeCapability.invokeHttp(target, unknown)
      // The typed invoke decodes only 2xx answers; any other status is thrown as InvokeHttpException.
      val typed =
        try
          InvokeCapability.invoke[StatsResponse](target, unknown)
          None
        catch case e: InvokeHttpException => Some(e.status)
      CallerResult(greetings, s, raw.status, typed)
