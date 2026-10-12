package hellostate

import dapr4s.*
import dapr4s.state.*

case class Note(text: String, revision: Int)

case class HelloStateResult(
    saved: Option[Note],
    etagConflict: Option[ETagMismatchException], // None = success
    afterUpdate: Option[Note],
    txnNoteA: Option[Note],
    txnNoteB: Option[Note],
    txnOriginal: Option[Note], // deleted key — should be None
    bulk: Seq[(String, Option[Note])],
)

// ── Capture-checked pure module ───────────────────────────────────────────────
// The whole capability scope is established here: DaprCapability.state opens
// a StateCapability.Instance bound to that store, used throughout.  JsonCodec[Note] is
// passed from the shell so macro derivation stays out of this module.
// ─────────────────────────────────────────────────────────────────────────────

object HelloStateApp:
  def apply()(using DaprCapability, JsonCodec[Note]): HelloStateResult =
    DaprCapability.state(StateStoreName("statestore")):
      val key = StateStoreKey("hello-note")

      StateCapability.Instance.save(key, Note("Hello from dapr4s!", 1))
      val saved = StateCapability.Instance.get[Note](key)

      val entry = StateCapability.Instance.getWithETag[Note](key)
      val etagConflict: Option[ETagMismatchException] =
        (entry.value, entry.etag) match
          case (Some(n), Some(etag)) =>
            val next = n.copy(text = "Updated!", revision = n.revision + 1)
            StateCapability.Instance.saveWithETag(key, next, etag)
          case _ => None

      val afterUpdate = StateCapability.Instance.get[Note](key)

      StateCapability.Instance.transaction(
        Seq(
          StateOp.UpsertOp[Note](StateStoreKey("note-a"), Note("A", 1)),
          StateOp.UpsertOp[Note](StateStoreKey("note-b"), Note("B", 1)),
          StateOp.DeleteOp(key),
        ),
      )

      val txnNoteA = StateCapability.Instance.get[Note](StateStoreKey("note-a"))
      val txnNoteB = StateCapability.Instance.get[Note](StateStoreKey("note-b"))
      val txnOriginal = StateCapability.Instance.get[Note](key)

      val bulkMap = StateCapability.Instance.getBulk[Note](Seq(StateStoreKey("note-a"), StateStoreKey("note-b")))
      val bulk = bulkMap.map((k, e) => (k.value, e.value)).toSeq

      HelloStateResult(saved, etagConflict, afterUpdate, txnNoteA, txnNoteB, txnOriginal, bulk)
