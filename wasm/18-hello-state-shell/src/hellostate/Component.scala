package hellostate

import dapr4s.*
import dapr4s.invoke.*
import dapr4s.state.*
import dapr4s.wasm.DaprComponent

// ── Impure shell (WASI component) ─────────────────────────────────────────────
// The Wasm twin of 01-hello-state-shell. A component has no `main` and no
// connection config: the dapr4s wasm wall hands the app a StateCapability backed by
// the component's WIT imports, and the app answers service invocations. So instead
// of printing, the same report is returned from an invoke route `run`:
//
//   curl -X POST localhost:$DAPR_HTTP_PORT/v1.0/invoke/hello-state/method/run
//
// The pure module is ../../01-hello-state, unchanged: HelloStateApp.run.
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
  given JsonCodec[Note] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[List[String]] = upickleCodec
  given JsonCodec[Unit] with
    def encode(value: Unit): String = "null"
    def decode(json: String | Null): Either[JsonDecodeException, Unit] = Right(())

import Codecs.given

/** The same lines 01-hello-state-shell prints, so the two runs compare line by line. */
def report(r: HelloStateResult): List[String] =
  List(
    s"saved:        ${r.saved}",
    s"etag save:    ${r.etagConflict.fold("ok")(e => s"conflict: ${e.getMessage}")}",
    s"after update: ${r.afterUpdate}",
    s"txn note-a:   ${r.txnNoteA}",
    s"txn note-b:   ${r.txnNoteB}",
    s"txn original: ${r.txnOriginal}",
    s"bulk read:    ${r.bulk.map((k, v) => s"$k=$v")}",
  )

object HelloStateComponent extends DaprComponent:
  override def app(using state: StateCapability): DaprApp =
    given StateCapability.Instance = state.instance(NoteStore)
    DaprApp(invokeRoutes =
      List(
        InvokeRoute[Unit, List[String]](InvokeMethodName("run"))(_ => report(HelloStateApp.run())),
      ),
    )
