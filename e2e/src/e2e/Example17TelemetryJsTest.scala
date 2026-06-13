package e2e

/** E2E for the 17 telemetry example on **Scala.js** — the JS twin of the *workload* of [[Example14ObservabilityTest]]
  * (the orders + pricing services), without the SigNoz/OpenTelemetry stack.
  *
  * The telemetry funnel (daprd → otel-collector → SigNoz) is platform-agnostic infrastructure already exercised by the
  * JVM example 14, so re-running it on Scala.js would validate nothing new. What IS Scala.js-specific is the app
  * workload: an `OrderWorkflow` whose activities span Dapr **service invocation** (orders → pricing for a quote) and
  * **pub/sub** (an order-completed event consumed by an audit subscription). This suite starts workflow instances via
  * the Dapr workflow HTTP API and asserts on their durable output — proving workflow + invocation + pub/sub compose
  * correctly on Node + JSPI.
  */
class Example17TelemetryJsTest extends E2ESuite:
  override val munitTimeout = scala.concurrent.duration.Duration(120, "s")

  // The JS suites run on their own CI leg (Node 25 + npm ci + linked Wasm bundles); the `core`
  // leg sets E2E_SKIP_JS=1 to skip them, mirroring how example 14 uses E2E_SKIP_OBSERVABILITY.
  override def munitIgnore: Boolean = sys.env.contains("E2E_SKIP_JS")

  val infra = MultiNodeInfra(
    composeFileName = "docker-compose.17-observability.yml",
    bundles = Map(
      "BUNDLE_ORDERS" -> "17-orders-shell",
      "BUNDLE_PRICING" -> "17-pricing-shell",
    ),
    daprServices = List("orders-dapr", "pricing-dapr"),
    appServices = List("orders"),
  )
  override def munitFixtures = List(infra)

  /** Start `OrderWorkflow` with the given input, poll until it completes, and return its decoded output JSON. */
  private def runWorkflow(instanceId: String, input: String): ujson.Value =
    val daprPort = infra.daprPort("orders")
    val (startStatus, startBody) =
      DaprHttp.post(daprPort, s"/v1.0-beta1/workflows/dapr/OrderWorkflow/start?instanceID=$instanceId", input)
    assertEquals(startStatus, 202, clue(startBody))

    val deadline = System.currentTimeMillis() + 60_000
    var output: Option[ujson.Value] = None
    while output.isEmpty && System.currentTimeMillis() < deadline do
      Thread.sleep(1_000)
      val (st, body) = DaprHttp.get(daprPort, s"/v1.0-beta1/workflows/dapr/$instanceId")
      if st == 200 then
        val j = ujson.read(body)
        if j("runtimeStatus").str == "COMPLETED" then
          // The workflow output is a JSON string nested under the instance's properties.
          output = Some(ujson.read(j("properties")("dapr.workflow.output").str))
    output.getOrElse(fail(s"workflow $instanceId did not complete within 60s"))

  test("workflow ships an in-stock, affordable order (invocation + pub/sub)") {
    // quantity <= 5 reserves OK; a huge budget always covers the pricing-service quote.
    val out = runWorkflow("js-wf-ship", """{"orderId":"WF-OK","item":"widget","quantity":2,"budget":1000000.0}""")
    assertEquals(out("success").bool, true, clue(out))
    assert(out("message").str.startsWith("shipped:"), clue(out))
  }

  test("workflow rejects an over-stock order as 'out of stock'") {
    val out = runWorkflow("js-wf-oos", """{"orderId":"WF-OOS","item":"widget","quantity":9,"budget":1000000.0}""")
    assertEquals(out("success").bool, false, clue(out))
    assertEquals(out("message").str, "out of stock", clue(out))
  }

  test("workflow declines payment when the budget cannot cover the quote") {
    // quantity <= 5 reserves OK, but a zero budget cannot cover any positive quote total.
    val out = runWorkflow("js-wf-pay", """{"orderId":"WF-PAY","item":"widget","quantity":2,"budget":0.0}""")
    assertEquals(out("success").bool, false, clue(out))
    assertEquals(out("message").str, "payment declined", clue(out))
  }
