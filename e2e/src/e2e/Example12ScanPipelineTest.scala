package e2e

/** E2E for the 08 Grafana-style scan pipeline.
  *
  * Boots gateway + worker + results, each with its own sidecar, sharing one Redis for pub/sub and state. Submitting a
  * scan to the gateway fans out over `scan-requested` to the worker, whose `scan-completed` events the results service
  * folds into a dashboard aggregate. Tests assert against *deltas* in that dashboard so they are independent of
  * ordering and of state accumulated by earlier cases.
  *
  * Covers the three resiliency concerns the example demonstrates: fan-out + aggregation, idempotent dedup of a
  * redelivered scanId, and retry of a transient ("flaky") failure until it eventually succeeds.
  */
class Example12ScanPipelineTest extends E2ESuite:
  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")

  val infra = MultiServerInfra(
    composeFileName = "docker-compose.12-scan.yml",
    jars = Map(
      "JAR_GATEWAY" -> "scan-gateway",
      "JAR_WORKER" -> "scan-worker",
      "JAR_RESULTS" -> "scan-results",
    ),
    daprServices = List("gateway-dapr", "worker-dapr", "results-dapr"),
    appServices = List("gateway", "results"),
  )
  override def munitFixtures = List(infra)

  private case class Snapshot(totalScans: Int, totalFindings: Int, critical: Int, deadLetters: Int)

  private def dashboard(): Snapshot =
    val (status, body) = DaprHttp.appPost(infra.appPort("results"), "/dashboard", "")
    assertEquals(status, 200, clue(body))
    val j = ujson.read(body)
    Snapshot(
      j("totalScans").num.toInt,
      j("totalFindings").num.toInt,
      j("critical").num.toInt,
      j("deadLetters").num.toInt,
    )

  private def submit(scanId: String, image: String, source: String): Unit =
    val body = s"""{"scanId":"$scanId","image":"$image","source":"$source"}"""
    val (status, resp) = DaprHttp.appPost(infra.appPort("gateway"), "/submit", body)
    assertEquals(status, 200, clue(resp))

  // Polls the dashboard until `p` holds (or fails after `timeoutMs`), returning the satisfying snapshot.
  private def waitForDashboard(timeoutMs: Long)(p: Snapshot => Boolean): Snapshot =
    val deadline = System.currentTimeMillis() + timeoutMs
    var last = dashboard()
    while !p(last) && System.currentTimeMillis() < deadline do
      Thread.sleep(500)
      last = dashboard()
    assert(p(last), clue(s"dashboard never satisfied predicate; last = $last"))
    last

  test("fan-out aggregates findings across scans") {
    val before = dashboard()
    submit("scan-nginx", "docker.io/library/nginx:1.27", "github") // 1 HIGH finding
    submit("scan-pay", "ecr/payments:sha-abc", "ecr") // 1 CRITICAL + 1 MEDIUM
    val after = waitForDashboard(60_000)(s => s.totalScans >= before.totalScans + 2)
    assertEquals(after.totalFindings - before.totalFindings, 3, clue((before, after)))
    assertEquals(after.critical - before.critical, 1, clue((before, after)))
  }

  test("duplicate scanId is deduplicated (processed once)") {
    val before = dashboard()
    submit("scan-dup", "ghcr.io/acme/api:1.0", "github")
    val once = waitForDashboard(60_000)(s => s.totalScans >= before.totalScans + 1)
    Thread.sleep(2_000) // ensure the worker has persisted the seen-marker
    submit("scan-dup", "ghcr.io/acme/api:1.0", "github") // redelivery — must be dropped
    Thread.sleep(8_000)
    val after = dashboard()
    assertEquals(after.totalScans, once.totalScans, clue(s"duplicate was re-processed: $once -> $after"))
  }

  test("flaky scan is retried until it succeeds") {
    val before = dashboard()
    submit("scan-flaky", "ghcr.io/acme/worker:2.0", "flaky") // first delivery NAKs, redelivery succeeds
    val after = waitForDashboard(120_000)(s => s.totalScans >= before.totalScans + 1)
    assert(after.totalScans >= before.totalScans + 1, clue((before, after)))
  }

  test("poison scan is dead-lettered after retries are exhausted") {
    val before = dashboard()
    submit("scan-poison", "ghcr.io/acme/bad:1.0", "poison") // always NAKs → routed to dead-letter topic
    val after = waitForDashboard(120_000)(s => s.deadLetters >= before.deadLetters + 1)
    assert(after.deadLetters >= before.deadLetters + 1, clue((before, after)))
    // A dead-lettered request must never count as a completed scan.
    assertEquals(after.totalScans, before.totalScans, clue((before, after)))
  }
