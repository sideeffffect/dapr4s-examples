package e2e

/** E2E for the 15 Grafana-style scan pipeline on **Scala.js** — the JS twin of [[Example12ScanPipelineTest]].
  *
  * Boots gateway + worker + results as Node 25 processes running the linked Wasm bundles, each with its own daprd
  * sidecar, sharing one Redis for pub/sub and state. The assertions are identical to the JVM example's (the dapr4s
  * public API and the shared `App.scala` are the same across platforms) — this suite validates that the published
  * dapr4s_sjs1_3 artifact drives the same pub/sub fan-out + aggregation, idempotent dedup, retry, and dead-lettering
  * end to end on Node + JSPI.
  */
class Example15ScanPipelineJsTest extends E2ESuite:
  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")

  // The JS suites run on their own CI leg (Node 25 + npm ci + linked Wasm bundles); the `core`
  // leg sets E2E_SKIP_JS=1 to skip them, mirroring how example 14 uses E2E_SKIP_OBSERVABILITY.
  override def munitIgnore: Boolean = sys.env.contains("E2E_SKIP_JS")

  val infra = MultiNodeInfra(
    composeFileName = "docker-compose.15-scan.yml",
    bundles = Map(
      "BUNDLE_GATEWAY" -> "15-scan-gateway-shell",
      "BUNDLE_WORKER" -> "15-scan-worker-shell",
      "BUNDLE_RESULTS" -> "15-scan-results-shell",
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
    submit("js-scan-nginx", "docker.io/library/nginx:1.27", "github") // 1 HIGH finding
    submit("js-scan-pay", "ecr/payments:sha-abc", "ecr") // 1 CRITICAL + 1 MEDIUM
    val after = waitForDashboard(60_000)(s => s.totalScans >= before.totalScans + 2)
    assertEquals(after.totalFindings - before.totalFindings, 3, clue((before, after)))
    assertEquals(after.critical - before.critical, 1, clue((before, after)))
  }

  test("duplicate scanId is deduplicated (processed once)") {
    val before = dashboard()
    submit("js-scan-dup", "ghcr.io/acme/api:1.0", "github")
    val once = waitForDashboard(60_000)(s => s.totalScans >= before.totalScans + 1)
    Thread.sleep(2_000) // ensure the worker has persisted the seen-marker
    submit("js-scan-dup", "ghcr.io/acme/api:1.0", "github") // redelivery — must be dropped
    Thread.sleep(8_000)
    val after = dashboard()
    assertEquals(after.totalScans, once.totalScans, clue(s"duplicate was re-processed: $once -> $after"))
  }

  test("flaky scan is retried until it succeeds") {
    val before = dashboard()
    submit("js-scan-flaky", "ghcr.io/acme/worker:2.0", "flaky") // first delivery NAKs, redelivery succeeds
    val after = waitForDashboard(120_000)(s => s.totalScans >= before.totalScans + 1)
    assert(after.totalScans >= before.totalScans + 1, clue((before, after)))
  }

  test("poison scan is dead-lettered after retries are exhausted") {
    val before = dashboard()
    submit("js-scan-poison", "ghcr.io/acme/bad:1.0", "poison") // always NAKs → routed to dead-letter topic
    val after = waitForDashboard(120_000)(s => s.deadLetters >= before.deadLetters + 1)
    assert(after.deadLetters >= before.deadLetters + 1, clue((before, after)))
    assertEquals(after.totalScans, before.totalScans, clue((before, after)))
  }
