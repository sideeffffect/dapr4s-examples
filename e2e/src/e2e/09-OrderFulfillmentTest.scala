package e2e

/** E2E for the 09 ZEISS-style order-fulfillment saga.
  *
  * Boots all four services (order + inventory + payment + shipping), each with its own sidecar. A POST to the
  * order-service `/submit-order` route runs the durable workflow synchronously (the handler waits for completion) — the
  * saga's activities reach the downstream services by app-id via Dapr service invocation, so a single assertion
  * exercises the full cross-service path including compensation.
  *
  * Each test uses a distinct SKU so inventory stock (persisted in Redis, DefaultStock=10) does not leak between cases.
  */
class OrderFulfillmentTest extends E2ESuite:
  override val munitTimeout = scala.concurrent.duration.Duration(120, "s")

  val infra = MultiServerInfra(
    composeFileName = "docker-compose.09-order.yml",
    jars = Map(
      "JAR_ORDER" -> "order-service",
      "JAR_INVENTORY" -> "inventory-service",
      "JAR_PAYMENT" -> "payment-service",
      "JAR_SHIPPING" -> "shipping-service",
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
      """{"orderId":"E2E-OK","sku":"e2e-widget","quantity":3,"amount":250.0,"address":"1 Market St"}""",
    )
    assertEquals(out("success").bool, true, clue(out))
    assert(out("message").str.startsWith("shipped:"), clue(out))
  }

  test("over-stock order fails with 'out of stock'") {
    val out = submit(
      """{"orderId":"E2E-OOS","sku":"e2e-gadget","quantity":50,"amount":100.0,"address":"2 Main St"}""",
    )
    assertEquals(out("success").bool, false, clue(out))
    assertEquals(out("message").str, "out of stock")
  }

  test("over-limit payment fails with 'payment declined' (reservation compensated)") {
    val out = submit(
      """{"orderId":"E2E-PAY","sku":"e2e-gizmo","quantity":2,"amount":5000.0,"address":"3 Side St"}""",
    )
    assertEquals(out("success").bool, false, clue(out))
    assertEquals(out("message").str, "payment declined")
  }

  test("empty address fails with 'dispatch failed' (refund + release compensated)") {
    val out = submit(
      """{"orderId":"E2E-SHIP","sku":"e2e-gear","quantity":1,"amount":75.0,"address":""}""",
    )
    assertEquals(out("success").bool, false, clue(out))
    assertEquals(out("message").str, "dispatch failed")
  }
