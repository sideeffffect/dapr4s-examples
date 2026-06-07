package scanresults

import dapr4s.*

// ── 08 · Grafana-style scan pipeline — results service ────────────────────────
// Subscribes to `scan-completed`, folds each result into a running dashboard
// aggregate in the state store, and exposes a `dashboard` invocation route so a
// UI (or the demo driver) can read the current totals.  This is the read side
// of the pipeline — the part that fed Grafana dashboards in the real system.
// ─────────────────────────────────────────────────────────────────────────────

val PubSubComponent = PubSubName("pubsub")
val StateStore = StateStoreName("statestore")
val ScanCompletedTopic = Topic("scan-completed")
val DeadLetterTopic = Topic("scan-dead-letter")
val DashboardKey = StateStoreKey("dashboard")

case class Finding(severity: String, cve: String)
case class ScanResult(scanId: String, image: String, findings: List[Finding], status: String)
case class ScanRequest(scanId: String, image: String, source: String)
case class Dashboard(totalScans: Int, totalFindings: Int, critical: Int, deadLetters: Int)

// Fold one result into the aggregate under optimistic concurrency: concurrent
// scan-completed events would otherwise read-modify-write the same key and lose
// updates. We compare-and-swap on the ETag and retry on conflict. The aggregate
// is seeded once at startup (see `ResultsApp`) so every update has an ETag.
def onScanCompleted(event: CloudEvent[ScanResult])(using
    StateCapability,
    JsonCodec[Dashboard],
): SubscriptionResult =
  val r = event.data
  updateDashboard(d =>
    d.copy(
      totalScans = d.totalScans + 1,
      totalFindings = d.totalFindings + r.findings.size,
      critical = d.critical + r.findings.count(_.severity == "CRITICAL"),
    ),
  )
  SubscriptionResult.Success

// Dead-lettered scan requests land here after the worker's retry policy is
// exhausted. We just tally them on the dashboard so the demo can surface the
// count; a real pipeline would inspect/replay the payload.
def onDeadLetter(event: CloudEvent[ScanRequest])(using
    StateCapability,
    JsonCodec[Dashboard],
): SubscriptionResult =
  updateDashboard(d => d.copy(deadLetters = d.deadLetters + 1))
  SubscriptionResult.Success

// Compare-and-swap a fold over the dashboard aggregate, retrying on ETag conflict.
@annotation.tailrec
def updateDashboard(f: Dashboard => Dashboard)(using StateCapability, JsonCodec[Dashboard]): Unit =
  val entry = StateCapability.getWithETag[Dashboard](DashboardKey)
  val updated = f(entry.value.getOrElse(Dashboard(0, 0, 0, 0)))
  entry.etag match
    case Some(tag) => if StateCapability.saveWithETag(DashboardKey, updated, tag).isDefined then updateDashboard(f)
    case None      => StateCapability.save(DashboardKey, updated)

def dashboard()(using StateCapability, JsonCodec[Dashboard]): Dashboard =
  StateCapability.get[Dashboard](DashboardKey).getOrElse(Dashboard(0, 0, 0, 0))

object ResultsApp:
  def apply()(using
      DaprCapability,
      JsonCodec[ScanResult],
      JsonCodec[ScanRequest],
      JsonCodec[Dashboard],
      JsonCodec[Unit],
  ): DaprApp =
    DaprCapability.state(StateStore):
      // Seed the aggregate once at startup so concurrent updates always have an
      // ETag to compare-and-swap against (avoids a lost-update race on first write).
      if StateCapability.getWithETag[Dashboard](DashboardKey).etag.isEmpty then
        StateCapability.save(DashboardKey, Dashboard(0, 0, 0, 0))
      DaprApp(
        subscriptions = List(
          Subscription[ScanResult](PubSubComponent, ScanCompletedTopic)(onScanCompleted),
          Subscription[ScanRequest](PubSubComponent, DeadLetterTopic)(onDeadLetter),
        ),
        invocations = List(
          InvocationRoute[Unit, Dashboard](InvocationMethodName("dashboard"))(_ => dashboard()),
        ),
      )
