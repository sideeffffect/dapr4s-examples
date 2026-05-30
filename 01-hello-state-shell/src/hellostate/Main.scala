package hellostate

import dapr4s.*

// ── Impure shell ──────────────────────────────────────────────────────────────
// Derives JsonCodec[Note] (macro derivation is impure), starts the Dapr
// runtime, establishes the capability scope, and prints the structured result
// returned by the pure helloStateApp.
// ─────────────────────────────────────────────────────────────────────────────

private def upickleCodec[T: upickle.default.ReadWriter]: JsonCodec[T] = new JsonCodec[T]:
  def encode(value: T): String = upickle.default.write(value)
  def decode(json: String | Null): Either[JsonDecodeException, T] =
    if json == null then Left(JsonDecodeException("null input"))
    else
      try Right(upickle.default.read[T](json))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

@scala.caps.assumeSafe
given JsonCodec[Note] = upickleCodec(using upickle.default.macroRW)

private def daprConfigFromEnv(): DaprConfig =
  val http = sys.env.getOrElse("DAPR_HTTP_PORT", "3500").toInt
  val grpc = sys.env.getOrElse("DAPR_GRPC_PORT", "50001").toInt
  DaprConfig(sidecar =
    SidecarConfig(
      httpEndpoint = java.net.URI.create(s"http://localhost:$http"),
      grpcEndpoint = java.net.URI.create(s"http://localhost:$grpc"),
      grpcTlsInsecure = false,
    ),
  )

@main def run(): Unit =
  println("=== 01 hello-state: state CRUD with capability scoping ===\n")
  Dapr(daprConfigFromEnv()).run:
    val r = helloStateApp()
    println(s"saved:        ${r.saved}")
    println(s"etag save:    ${if r.etagConflict.isEmpty then "ok" else s"conflict: ${r.etagConflict.get.getMessage}"}")
    println(s"after update: ${r.afterUpdate}")
    println(s"txn note-a:   ${r.txnNoteA}")
    println(s"txn note-b:   ${r.txnNoteB}")
    println(s"txn original: ${r.txnOriginal}")
    println(s"bulk read:    ${r.bulk.map((k, v) => s"$k=$v")}")
