package hellostate

import dapr4s.*

case class Note(text: String, revision: Int)

case class HelloStateResult(
  saved:        Option[Note],
  etagConflict: Option[ETagMismatchException],  // None = success
  afterUpdate:  Option[Note],
  txnNoteA:     Option[Note],
  txnNoteB:     Option[Note],
  txnOriginal:  Option[Note],  // deleted key — should be None
  bulk:         Seq[(String, Option[Note])],
)

// ── Capture-checked pure module ───────────────────────────────────────────────
// The whole capability scope is established here: DaprCapability.state opens
// the StateCapability, which is then used throughout.  JsonCodec[Note] is
// passed from the shell so macro derivation stays out of this module.
// ─────────────────────────────────────────────────────────────────────────────

def helloStateApp()(using DaprCapability, JsonCodec[Note]): HelloStateResult =
  DaprCapability.state(StoreName("statestore")):
    val key = StateKey("hello-note")

    StateCapability.save(key, Note("Hello from dapr4s!", 1))
    val saved = StateCapability.get[Note](key)

    val entry = StateCapability.getWithETag[Note](key)
    val etagConflict: Option[ETagMismatchException] =
      (entry.value, entry.etag) match
        case (Some(n), Some(etag)) =>
          val next = n.copy(text = "Updated!", revision = n.revision + 1)
          StateCapability.saveWithETag(key, next, etag)
        case _ => None

    val afterUpdate = StateCapability.get[Note](key)

    StateCapability.transaction(Seq(
      StateOp.UpsertOp[Note](StateKey("note-a"), Note("A", 1)),
      StateOp.UpsertOp[Note](StateKey("note-b"), Note("B", 1)),
      StateOp.DeleteOp(key),
    ))

    val txnNoteA    = StateCapability.get[Note](StateKey("note-a"))
    val txnNoteB    = StateCapability.get[Note](StateKey("note-b"))
    val txnOriginal = StateCapability.get[Note](key)

    val bulkMap = StateCapability.getBulk[Note](Seq(StateKey("note-a"), StateKey("note-b")))
    val bulk    = bulkMap.map((k, e) => (k.value, e.value)).toSeq

    HelloStateResult(saved, etagConflict, afterUpdate, txnNoteA, txnNoteB, txnOriginal, bulk)
