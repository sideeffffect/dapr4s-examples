package e2e

/** E2E for the 16 ZEISS-style order-fulfillment saga on **Scala.js** — the JS twin of
  * [[Example13OrderFulfillmentTest]].
  *
  * Boots all four services (order + inventory + payment + shipping) as Node 25 processes running the linked Wasm
  * bundles, each with its own daprd sidecar. A POST to the order-service `/submit-order` route runs the durable Dapr
  * workflow synchronously; the saga's activities reach the downstream services by app-id via Dapr service invocation. A
  * single assertion per case therefore exercises the full cross-service path including compensation — proving the
  * Scala.js workflow runtime, activities, and service invocation all work end to end on Node + JSPI.
  *
  * Distinct SKUs keep inventory stock (persisted in Redis, DefaultStock=10) from leaking between cases.
  */
class Example16OrderFulfillmentJsTest extends E2ESuite:
  override val munitTimeout = scala.concurrent.duration.Duration(120, "s")

  // The JS suites run on their own CI leg (Node 25 + npm ci + linked Wasm bundles); the `core`
  // leg sets E2E_SKIP_JS=1 to skip them, mirroring how example 14 uses E2E_SKIP_OBSERVABILITY.
  override def munitIgnore: Boolean = sys.env.contains("E2E_SKIP_JS")

  val infra = MultiNodeInfra(
    composeFileName = "docker-compose.16-order.yml",
    bundles = Map(
      "BUNDLE_ORDER" -> "16-order-service-shell",
      "BUNDLE_INVENTORY" -> "16-inventory-service-shell",
      "BUNDLE_PAYMENT" -> "16-payment-service-shell",
      "BUNDLE_SHIPPING" -> "16-shipping-service-shell",
    ),
    daprServices = List("order-dapr", "inventory-dapr", "payment-dapr", "shipping-dapr"),
    appServices = List("order"),
  )
  override def munitFixtures = List(infra)

  private def submit(order: String): ujson.Value =
    val (status, body) = DaprHttp.appPost(infra.appPort("order"), "/submit-order", order)
    assertEquals(status, 200, clue(body))
    ujson.read(body)

  test("in-stock, affordable, addressed order ships") {
    val out = submit(
      """{"orderId":"JS-OK","sku":"js-widget","quantity":3,"amount":250.0,"address":"1 Market St"}""",
    )
    assertEquals(out("success").bool, true, clue(out))
    assert(out("message").str.startsWith("shipped:"), clue(out))
  }

  test("over-stock order fails with 'out of stock'") {
    val out = submit(
      """{"orderId":"JS-OOS","sku":"js-gadget","quantity":50,"amount":100.0,"address":"2 Main St"}""",
    )
    assertEquals(out("success").bool, false, clue(out))
    assertEquals(out("message").str, "out of stock")
  }

  test("over-limit payment fails with 'payment declined' (reservation compensated)") {
    val out = submit(
      """{"orderId":"JS-PAY","sku":"js-gizmo","quantity":2,"amount":5000.0,"address":"3 Side St"}""",
    )
    assertEquals(out("success").bool, false, clue(out))
    assertEquals(out("message").str, "payment declined")
  }

  test("empty address fails with 'dispatch failed' (refund + release compensated)") {
    val out = submit(
      """{"orderId":"JS-SHIP","sku":"js-gear","quantity":1,"amount":75.0,"address":""}""",
    )
    assertEquals(out("success").bool, false, clue(out))
    assertEquals(out("message").str, "dispatch failed")
  }
