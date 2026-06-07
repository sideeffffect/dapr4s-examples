package scanworker

import dapr4s.*
import dapr4s.derivation.*

// ── 08 · Grafana-style scan pipeline — worker service ─────────────────────────
// Subscribes to `scan-requested`, runs the (stubbed) image scan, and publishes
// findings to `scan-completed`.  It demonstrates the three resiliency concerns
// the real Grafana pipeline relies on Dapr for:
//
//   • Idempotency  — pub/sub is at-least-once, so a `seen-<scanId>` marker in the
//     state store lets a re-delivered request be dropped instead of re-scanned.
//   • Retry        — a transient fetch failure returns SubscriptionResult.Retry;
//     the sidecar redelivers per the subscription's resiliency policy.
//   • Dead-letter  — a request that keeps failing past the policy's max retries is
//     routed by the sidecar to the dead-letter topic (`deadLetterTopic` on the
//     Subscription below), where the results service records it.
// ─────────────────────────────────────────────────────────────────────────────

val PubSubComponent = PubSubName("pubsub")
val StateStore = StateStoreName("statestore")

case class ScanRequest(scanId: String, image: String, source: String)
case class Finding(severity: String, cve: String)
case class ScanResult(scanId: String, image: String, findings: List[Finding], status: String)
case class SeenMarker(scanId: String)

def seenKey(scanId: String): StateStoreKey = StateStoreKey(s"seen-$scanId")
def attemptKey(scanId: String): StateStoreKey = StateStoreKey(s"attempt-$scanId")

// Deterministic stand-in for a real scanner (Trivy/Grype): findings derived
// from the image string so the demo is reproducible.
def scan(req: ScanRequest): ScanResult =
  val findings =
    if req.image.contains("nginx") then List(Finding("HIGH", "CVE-2024-7347"))
    else if req.image.contains("payments") then
      List(Finding("CRITICAL", "CVE-2024-3094"), Finding("MEDIUM", "CVE-2023-44487"))
    else Nil
  ScanResult(req.scanId, req.image, findings, status = "scanned")

// Derived publisher: method (@name) → Topic.
trait ScanTopics:
  @name("scan-completed") def scanCompleted(r: ScanResult)(using PubSubCapability, JsonCodec[ScanResult]): Unit
object ScanTopics extends PubSub.Derived[ScanTopics]

// Derived subscription: method name (@name) → Topic, @deadLetter → dead-letter topic. The handler
// body keeps the explicit StateCapability calls — idempotency/retry logic over *dynamic* keys
// (seen-/attempt-<scanId>) has no derived form; only the wiring is derived.
object WorkerRoutes:
  @name("scan-requested")
  @deadLetter("scan-dead-letter")
  def onScanRequested(event: CloudEvent[ScanRequest])(using
      StateCapability,
      PubSubCapability,
      JsonCodec[SeenMarker],
      JsonCodec[Int],
      JsonCodec[ScanResult],
  ): SubscriptionResult =
    val req = event.data
    if StateCapability.get[SeenMarker](seenKey(req.scanId)).isDefined then SubscriptionResult.Drop
    else if req.source == "poison" then SubscriptionResult.Retry
    else
      val attempts = StateCapability.get[Int](attemptKey(req.scanId)).getOrElse(0)
      StateCapability.save(attemptKey(req.scanId), attempts + 1)
      if req.source == "flaky" && attempts == 0 then SubscriptionResult.Retry
      else
        ScanTopics.derive.scanCompleted(scan(req))
        StateCapability.save(seenKey(req.scanId), SeenMarker(req.scanId))
        SubscriptionResult.Success

object WorkerApp:
  def apply()(using
      DaprCapability,
      JsonCodec[ScanRequest],
      JsonCodec[SeenMarker],
      JsonCodec[Int],
      JsonCodec[ScanResult],
  ): DaprApp =
    DaprCapability.state(StateStore):
      DaprCapability.pubsub(PubSubComponent):
        DaprApp(subscriptions = Subscriptions.derive[WorkerRoutes.type](PubSubComponent))
