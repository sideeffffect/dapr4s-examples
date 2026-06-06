package e2e

import scala.concurrent.duration.Duration

/** Demo switch.
  *
  * `false` (default): runs as a normal, bounded E2E in the suite with rigorous assertions.
  *
  * `true`: keeps the whole stack up forever with fixed, memorable dashboard URLs and a gentle trickle of orders, so the
  * SigNoz and Diagrid dashboards can be demoed live without the test tearing down mid-presentation. Stop with Ctrl-C.
  */
object Example14Observability:
  val PresentationMode: Boolean = false

/** E2E for the 14 observability example: prove that traces, metrics, and logs all reach SigNoz and that workflows are
  * inspectable, then (optionally) hold the stack open for a live demo.
  */
class Example14ObservabilityTest extends E2ESuite:
  import Example14Observability.PresentationMode

  // CI parallelisation: this is the heaviest suite (SigNoz + ClickHouse), so it runs
  // on its own runner. The `core` CI job sets E2E_SKIP_OBSERVABILITY=1 to skip it here
  // (no fixture boot) while a dedicated job runs only this suite in parallel. Also handy
  // locally to skip the heavy stack during ordinary `./mill e2e.testForked` runs.
  override def munitIgnore: Boolean = sys.env.contains("E2E_SKIP_OBSERVABILITY")

  // Presentation mode must never time out; CI mode gets a generous bound for the heavy stack.
  override val munitTimeout: Duration = if PresentationMode then Duration.Inf else Duration(12, "min")

  val infra = ObservabilityInfra(PresentationMode)
  override def munitFixtures = List(infra)

  // ── An order mix that exercises every workflow branch (varied span trees) ──────
  private case class Sample(id: String, item: String, quantity: Int, budget: Double, expect: String)
  private val samples = List(
    Sample("obs-shipped-1", "widget", 2, 1000.0, "shipped"),
    Sample("obs-shipped-2", "gadget", 4, 1000.0, "shipped"),
    Sample("obs-declined-1", "gizmo", 3, 0.50, "payment declined"),
    Sample("obs-oos-1", "anvil", 9, 1000.0, "out of stock"),
  )
  private def json(s: Sample): String =
    s"""{"orderId":"${s.id}","item":"${s.item}","quantity":${s.quantity},"budget":${s.budget}}"""

  private def startOrder(id: String, body: String): Int =
    DaprHttp.post(infra.ordersDaprPort, s"/v1.0-beta1/workflows/dapr/OrderWorkflow/start?instanceID=$id", body)._1

  private def pollUntilComplete(id: String, timeoutMs: Long = 60_000): ujson.Value =
    val deadline = System.currentTimeMillis() + timeoutMs
    while System.currentTimeMillis() < deadline do
      val (_, body) = DaprHttp.get(infra.ordersDaprPort, s"/v1.0-beta1/workflows/dapr/$id")
      val json = ujson.read(body)
      val status = json.obj.get("runtimeStatus").map(_.str).getOrElse("")
      if status == "COMPLETED" || status == "FAILED" || status == "TERMINATED" then return json
      Thread.sleep(500)
    throw RuntimeException(s"Workflow $id did not complete within ${timeoutMs}ms")

  // ── Prometheus text helpers ────────────────────────────────────────────────────
  private def scrape(port: Int, path: String = "/metrics"): String =
    DaprHttp.get(port, path)._2

  /** Sum every sample of the named counter. Tolerates the Prometheus `_total` suffix that exposition adds to counters
    * (e.g. `otelcol_exporter_sent_spans_total{...}`) and any label set: matches lines whose metric token is `name` or
    * `name_total`.
    */
  private def sumMetric(text: String, name: String): Double =
    text.linesIterator
      .filterNot(_.startsWith("#"))
      .flatMap { line =>
        val t = line.trim
        val end = t.indexWhere(c => c == '{' || c == ' ')
        if end <= 0 then None
        else
          val metric = t.substring(0, end)
          if metric == name || metric == name + "_total" then t.split("\\s+").lastOption.flatMap(_.toDoubleOption)
          else None
      }
      .sum

  private def awaitExported(name: String, timeoutMs: Long = 90_000): Double =
    val deadline = System.currentTimeMillis() + timeoutMs
    var last = 0.0
    while System.currentTimeMillis() < deadline do
      last = sumMetric(scrape(infra.collectorMetricsPort), name)
      if last > 0 then return last
      Thread.sleep(1000)
    // Timed out at zero — dump the collector's own export counters to make the
    // failure self-diagnosing (did the signal never arrive, or is the name off?).
    val dump = scrape(infra.collectorMetricsPort).linesIterator
      .filter(l => l.contains("otelcol_exporter_sent") || l.contains("otelcol_receiver_accepted"))
      .take(20)
      .mkString("\n")
    println(s"[await:$name] timed out at 0. collector export/receive counters:\n$dump")
    last

  test("observability: traces, metrics, and logs reach SigNoz; workflows are inspectable") {
    // 1. Drive the workload — start every sample and confirm the outcomes.
    samples.foreach(s => assertEquals(startOrder(s.id, json(s)), 202, clue(s)))
    samples.foreach { s =>
      val result = pollUntilComplete(s.id)
      assertEquals(result("runtimeStatus").str, "COMPLETED", clue(s))
      val out = ujson.read(result("properties")("dapr.workflow.output").str)
      if s.expect == "shipped" then assert(out("message").str.startsWith("shipped:"), clue(out))
      else assertEquals(out("message").str, s.expect, clue(s))
    }

    if PresentationMode then presentForever()
    else
      // 2. Metrics at the source: the orders daprd exposes Dapr Prometheus metrics.
      val daprMetrics = scrape(infra.ordersMetricsPort)
      assert(daprMetrics.contains("dapr_"), clue("expected dapr_* metrics on the sidecar"))

      // 3. All three signals were exported to SigNoz. The collector's own pipeline
      //    counters are the auth-free, deterministic proof that each signal flowed
      //    through the funnel and was sent on to SigNoz.
      val spans = awaitExported("otelcol_exporter_sent_spans")
      val points = awaitExported("otelcol_exporter_sent_metric_points")
      val logs = awaitExported("otelcol_exporter_sent_log_records")
      assert(spans > 0, clue(s"traces exported to SigNoz (otelcol_exporter_sent_spans=$spans)"))
      assert(points > 0, clue(s"metrics exported to SigNoz (otelcol_exporter_sent_metric_points=$points)"))
      assert(logs > 0, clue(s"logs exported to SigNoz (otelcol_exporter_sent_log_records=$logs)"))

      // 4. The SigNoz backend is up and able to serve queries.
      assertEquals(DaprHttp.get(infra.signozPort, "/api/v1/health")._1, 200, clue("SigNoz health"))

      // 5. The Diagrid dashboard is reachable (it reads workflow state from Redis).
      assert(DaprHttp.get(infra.diagridPort, "/")._1 < 500, clue("Diagrid dashboard reachable"))
  }

  // ── Presentation mode: hold the stack open and keep the dashboards live ─────────
  private def presentForever(): Unit =
    val bar = "═" * 70
    println(s"""
       |$bar
       |  dapr4s · 14 · Observability + the Diagrid Dashboard — PRESENTATION MODE
       |$bar
       |  SigNoz (traces + metrics + logs):  http://localhost:3301
       |     • Traces   → Traces tab; service map shows orders → pricing
       |     • Metrics  → Dashboards; dapr_* runtime/http metrics
       |     • Logs     → Logs tab; app + daprd stdout
       |  Diagrid dashboard (workflow state):  http://localhost:8080
       |     • Live OrderWorkflow instances, inputs/outputs, event history
       |
       |  A gentle trickle of orders keeps flowing so the dashboards stay live.
       |  Press Ctrl-C to stop.
       |$bar
       |""".stripMargin)
    var n = 0
    while true do
      n += 1
      val s = samples(n % samples.size)
      val id = s"${s.id}-live-$n"
      val code = startOrder(id, json(s.copy(id = id)))
      println(s"[present] started order $id (HTTP $code)")
      Thread.sleep(3000)
