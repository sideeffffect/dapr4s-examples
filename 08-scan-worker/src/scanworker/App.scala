package scanworker

import dapr4s.*

// ── 08 · Grafana-style scan pipeline — worker service ─────────────────────────
// Subscribes to `scan-requested`, runs the (stubbed) image scan, and publishes
// findings to `scan-completed`.  It demonstrates the three resiliency concerns
// the real Grafana pipeline relies on Dapr for:
//
//   • Idempotency  — pub/sub is at-least-once, so a `seen-<scanId>` marker in the
//     state store lets a re-delivered request be dropped instead of re-scanned.
//   • Retry        — a transient fetch failure returns SubscriptionResult.Retry;
//     the sidecar redelivers per the subscription's resiliency policy.
//   • Dead-letter  — after the policy's max retries the sidecar routes the event
//     to the DLQ topic (configured in the subscription YAML, not in code).
// ─────────────────────────────────────────────────────────────────────────────

val PubSubComponent = PubSubName("pubsub")
val StateStore = StoreName("statestore")
val ScanRequestedTopic = Topic("scan-requested")
val ScanCompletedTopic = Topic("scan-completed")

case class ScanRequest(scanId: String, image: String, source: String)
case class Finding(severity: String, cve: String)
case class ScanResult(scanId: String, image: String, findings: List[Finding], status: String)
case class SeenMarker(scanId: String)

def seenKey(scanId: String): StateKey = StateKey(s"seen-$scanId")
def attemptKey(scanId: String): StateKey = StateKey(s"attempt-$scanId")

// Deterministic stand-in for a real scanner (Trivy/Grype): findings derived
// from the image string so the demo is reproducible.
def scan(req: ScanRequest): ScanResult =
  val findings =
    if req.image.contains("nginx") then List(Finding("HIGH", "CVE-2024-7347"))
    else if req.image.contains("payments") then
      List(Finding("CRITICAL", "CVE-2024-3094"), Finding("MEDIUM", "CVE-2023-44487"))
    else Nil
  ScanResult(req.scanId, req.image, findings, status = "scanned")

def onScanRequested(event: CloudEvent[ScanRequest])(using
    StateCapability,
    PubSubCapability,
    JsonCodec[SeenMarker],
    JsonCodec[Int],
    JsonCodec[ScanResult],
): SubscriptionResult =
  val req = event.data
  if StateCapability.get[SeenMarker](seenKey(req.scanId)).isDefined then
    // Already completed — at-least-once redelivery, safe to discard.
    SubscriptionResult.Drop
  else
    val attempts = StateCapability.get[Int](attemptKey(req.scanId)).getOrElse(0)
    StateCapability.save(attemptKey(req.scanId), attempts + 1)
    if req.source == "flaky" && attempts == 0 then
      // Simulated transient failure on first delivery — ask for redelivery.
      SubscriptionResult.Retry
    else
      val result = scan(req)
      PubSubCapability.publish(ScanCompletedTopic, result)
      StateCapability.save(seenKey(req.scanId), SeenMarker(req.scanId))
      SubscriptionResult.Success

def workerApp()(using
    DaprCapability,
    JsonCodec[ScanRequest],
    JsonCodec[SeenMarker],
    JsonCodec[Int],
    JsonCodec[ScanResult],
): DaprApp =
  DaprCapability.state(StateStore):
    DaprCapability.pubsub(PubSubComponent):
      DaprApp(subscriptions =
        List(
          Subscription[ScanRequest](PubSubComponent, ScanRequestedTopic)(onScanRequested),
        ),
      )
