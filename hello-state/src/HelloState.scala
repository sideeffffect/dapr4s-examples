package hellostate

import dapr4s.*

// ── Safe Scala guarantee ────────────────────────────────────────────────────
// StateCapability is scoped to the `DaprCapability.state { }` block.
// Try extracting it with `val cap = summon[StateCapability]` outside that
// block and the compiler will reject it — the capability's capture set does
// not extend past the lambda boundary.
// ─────────────────────────────────────────────────────────────────────────────

// Domain type.  JsonCodec[Note] is derived transitively via upickle's
// ReadWriter.  The @assumeSafe annotation marks the macro derivation as
// a trusted boundary: upickle is not capture-aware, but encoding/decoding
// JSON is a pure operation with no observable side effects.
case class Note(text: String, revision: Int)

@scala.caps.assumeSafe
object Note:
  given upickle.default.ReadWriter[Note] = upickle.default.macroRW

@main def run(): Unit =
  println("=== 01 hello-state: state CRUD with capability scoping ===\n")

  DaprRuntime.run(DaprRuntimeConfig()):

    DaprCapability.state(StoreName("statestore")):

      val key = StateKey("hello-note")

      // ── Create ──────────────────────────────────────────────────────────
      StateCapability.save(key, Note("Hello from dapr4s!", 1))
      println(s"saved:        ${StateCapability.get[Note](key)}")

      // ── ETag-guarded optimistic update ──────────────────────────────────
      // getWithETag returns both the value and its current server-side ETag.
      // saveWithETag will fail (returning Some(ETagMismatchException)) if
      // another writer raced us.  This is optimistic concurrency without locks.
      val entry = StateCapability.getWithETag[Note](key)
      for n <- entry.value; etag <- entry.etag do
        val next     = n.copy(text = "Updated!", revision = n.revision + 1)
        val conflict = StateCapability.saveWithETag(key, next, etag)
        println(s"etag save:    ${if conflict.isEmpty then "ok" else s"conflict: $conflict"}")

      println(s"after update: ${StateCapability.get[Note](key)}")

      // ── Atomic multi-key transaction ─────────────────────────────────────
      StateCapability.transaction(Seq(
        StateOp.UpsertOp[Note](StateKey("note-a"), Note("A", 1)),
        StateOp.UpsertOp[Note](StateKey("note-b"), Note("B", 1)),
        StateOp.DeleteOp(key),
      ))
      println(s"txn note-a:   ${StateCapability.get[Note](StateKey("note-a"))}")
      println(s"txn note-b:   ${StateCapability.get[Note](StateKey("note-b"))}")
      println(s"txn original: ${StateCapability.get[Note](key)}")

      // ── Bulk read ────────────────────────────────────────────────────────
      val bulk = StateCapability.getBulk[Note](Seq(StateKey("note-a"), StateKey("note-b")))
      println(s"bulk read:    ${bulk.map((k, e) => s"${k.value}=${e.value}")}")
