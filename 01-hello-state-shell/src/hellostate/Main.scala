package hellostate

import dapr4s.*

// ── Impure shell ──────────────────────────────────────────────────────────────
// Derives JsonCodec[Note] (macro derivation is impure), starts the Dapr
// runtime, establishes the capability scope, and prints the structured result
// returned by the pure helloStateApp.
// ─────────────────────────────────────────────────────────────────────────────

@scala.caps.assumeSafe
given upickle.default.ReadWriter[Note] = upickle.default.macroRW

private def daprConfigFromEnv(): DaprRuntimeConfig =
  val http = sys.env.getOrElse("DAPR_HTTP_PORT", "3500").toInt
  val grpc = sys.env.getOrElse("DAPR_GRPC_PORT", "50001").toInt
  DaprRuntimeConfig(sidecar =
    SidecarConfig(
      httpEndpoint = java.net.URI.create(s"http://localhost:$http"),
      grpcEndpoint = java.net.URI.create(s"http://localhost:$grpc"),
      grpcTlsInsecure = false,
    ),
  )

@main def run(): Unit =
  println("=== 01 hello-state: state CRUD with capability scoping ===\n")
  DaprRuntime.run(daprConfigFromEnv()):
    val r = helloStateApp()
    println(s"saved:        ${r.saved}")
    println(s"etag save:    ${if r.etagConflict.isEmpty then "ok" else s"conflict: ${r.etagConflict.get.getMessage}"}")
    println(s"after update: ${r.afterUpdate}")
    println(s"txn note-a:   ${r.txnNoteA}")
    println(s"txn note-b:   ${r.txnNoteB}")
    println(s"txn original: ${r.txnOriginal}")
    println(s"bulk read:    ${r.bulk.map((k, v) => s"$k=$v")}")
