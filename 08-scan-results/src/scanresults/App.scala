package scanresults

import dapr4s.*

// ── 08 · Grafana-style scan pipeline — results service ────────────────────────
// Subscribes to `scan-completed`, folds each result into a running dashboard
// aggregate in the state store, and exposes a `dashboard` invocation route so a
// UI (or the demo driver) can read the current totals.  This is the read side
// of the pipeline — the part that fed Grafana dashboards in the real system.
// ─────────────────────────────────────────────────────────────────────────────

val PubSubComponent = PubSubName("pubsub")
val StateStore = StoreName("statestore")
val ScanCompletedTopic = Topic("scan-completed")
val DashboardKey = StateKey("dashboard")

case class Finding(severity: String, cve: String)
case class ScanResult(scanId: String, image: String, findings: List[Finding], status: String)
case class Dashboard(totalScans: Int, totalFindings: Int, critical: Int)

// Fold one result into the aggregate under optimistic concurrency: concurrent
// scan-completed events would otherwise read-modify-write the same key and lose
// updates. We compare-and-swap on the ETag and retry on conflict. The aggregate
// is seeded once at startup (see `resultsApp`) so every update has an ETag.
def onScanCompleted(event: CloudEvent[ScanResult])(using
    StateCapability,
    JsonCodec[Dashboard],
): SubscriptionResult =
  val r = event.data
  @annotation.tailrec
  def applyDelta(): Unit =
    val entry = StateCapability.getWithETag[Dashboard](DashboardKey)
    val current = entry.value.getOrElse(Dashboard(0, 0, 0))
    val updated = current.copy(
      totalScans = current.totalScans + 1,
      totalFindings = current.totalFindings + r.findings.size,
      critical = current.critical + r.findings.count(_.severity == "CRITICAL"),
    )
    entry.etag match
      case Some(tag) => if StateCapability.saveWithETag(DashboardKey, updated, tag).isDefined then applyDelta()
      case None      => StateCapability.save(DashboardKey, updated)
  applyDelta()
  SubscriptionResult.Success

def dashboard()(using StateCapability, JsonCodec[Dashboard]): Dashboard =
  StateCapability.get[Dashboard](DashboardKey).getOrElse(Dashboard(0, 0, 0))

def resultsApp()(using
    DaprCapability,
    JsonCodec[ScanResult],
    JsonCodec[Dashboard],
    JsonCodec[Unit],
): DaprApp =
  DaprCapability.state(StateStore):
    // Seed the aggregate once at startup so concurrent updates always have an
    // ETag to compare-and-swap against (avoids a lost-update race on first write).
    if StateCapability.getWithETag[Dashboard](DashboardKey).etag.isEmpty then
      StateCapability.save(DashboardKey, Dashboard(0, 0, 0))
    DaprApp(
      subscriptions = List(
        Subscription[ScanResult](PubSubComponent, ScanCompletedTopic)(onScanCompleted),
      ),
      invocations = List(
        InvocationRoute[Unit, Dashboard](MethodName("dashboard"))(_ => dashboard()),
      ),
    )
