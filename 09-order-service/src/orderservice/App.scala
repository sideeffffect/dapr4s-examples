package orderservice

import dapr4s.*

// ── 09 · ZEISS-style order fulfillment — order service (domain model) ─────────
// This pure, capture-checked module holds only the safe domain model: the order
// request/result types, the downstream service contracts, and the identifiers
// used to address them.  The saga orchestration itself performs I/O at every
// step (each activity calls a downstream service), so it is inherently impure
// and lives in the shell — see Main.scala.  The boundary is exactly the
// pure/shell split every example in this repo follows.
// ─────────────────────────────────────────────────────────────────────────────

case class OrderRequest(orderId: String, sku: String, quantity: Int, amount: Double, address: String)
case class OrderResult(success: Boolean, message: String)
case class ProcessOrderResult(orderId: String, timedOut: Boolean, result: Option[OrderResult])

// Downstream request/response shapes (this service's own copies — each
// microservice owns its contract; they agree by JSON structure on the wire).
case class ReserveRequest(orderId: String, sku: String, quantity: Int)
case class ReservationResult(reserved: Boolean, reservationId: String)
case class ReleaseRequest(reservationId: String, sku: String, quantity: Int)
case class ChargeRequest(orderId: String, amount: Double)
case class PaymentResult(charged: Boolean, transactionId: String)
case class RefundRequest(transactionId: String, amount: Double)
case class ShipRequest(orderId: String, address: String)
case class ShipmentResult(dispatched: Boolean, trackingId: String)

val InventoryService = AppId("inventory-service")
val PaymentService = AppId("payment-service")
val ShippingService = AppId("shipping-service")
val OrderWorkflowName = WorkflowName("OrderProcessingWorkflow")

// Sample orders that exercise each saga outcome: success, out-of-stock,
// payment-declined, and dispatch-failure (which triggers full compensation).
def sampleOrders: List[OrderRequest] =
  List(
    OrderRequest("ORD-001", sku = "widget", quantity = 3, amount = 250.0, address = "1 Market St"),
    OrderRequest("ORD-002", sku = "gadget", quantity = 50, amount = 100.0, address = "2 Main St"),
    OrderRequest("ORD-003", sku = "gizmo", quantity = 2, amount = 5000.0, address = "3 Side St"),
    OrderRequest("ORD-004", sku = "gear", quantity = 1, amount = 75.0, address = ""),
  )
