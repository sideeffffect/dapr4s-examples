package serviceinvocation

import dapr4s.*
import dapr4s.state.*
import dapr4s.wasm.DaprComponent

// ── Impure shell (WASI component) ─────────────────────────────────────────────
// The Wasm twin of 04-service-invocation-shell's `callee`: the greeting-service as a
// WASI component. It serves the very same DaprApp (CalleeApp.app from the pure module
// ../../04-service-invocation, unchanged), so 04's JVM `caller` calls it exactly as it
// calls the JVM callee — same app id, same routes, same JSON.
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
  given JsonCodec[GreetRequest] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[GreetResponse] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[StatsResponse] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[ServiceStats] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[Unit] with
    def encode(value: Unit): String = "null"
    def decode(json: String | Null): Either[JsonDecodeException, Unit] = Right(())

import Codecs.given

object GreetingServiceComponent extends DaprComponent:
  override def app(using state: StateCapability): DaprApp =
    given StateCapability.Instance = state.instance(StatStore)
    CalleeApp.app()
