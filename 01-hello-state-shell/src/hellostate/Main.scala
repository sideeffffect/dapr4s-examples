package hellostate

import dapr4s.*

// ── Impure shell ──────────────────────────────────────────────────────────────
// Derives JsonCodec[Note] (macro derivation is impure), starts the Dapr
// runtime, establishes the capability scope, and prints the structured result
// returned by the pure helloStateApp.
// ─────────────────────────────────────────────────────────────────────────────

@scala.caps.assumeSafe
given upickle.default.ReadWriter[Note] = upickle.default.macroRW

@main def run(): Unit =
  println("=== 01 hello-state: state CRUD with capability scoping ===\n")
  DaprRuntime.run(DaprRuntimeConfig()):
    val r = helloStateApp()
    println(s"saved:        ${r.saved}")
    println(s"etag save:    ${if r.etagConflict.isEmpty then "ok" else s"conflict: ${r.etagConflict.get.getMessage}"}")
    println(s"after update: ${r.afterUpdate}")
    println(s"txn note-a:   ${r.txnNoteA}")
    println(s"txn note-b:   ${r.txnNoteB}")
    println(s"txn original: ${r.txnOriginal}")
    println(s"bulk read:    ${r.bulk.map((k, v) => s"$k=$v")}")
