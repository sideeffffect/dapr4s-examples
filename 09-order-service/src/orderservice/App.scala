package orderservice

import dapr4s.*
import scala.concurrent.duration.*

// ── 09 · ZEISS-style order fulfillment — order service (domain model + driver) ─
// This pure, capture-checked module holds everything that does NOT need to
// capture a capability in a long-lived object: the order request/result types,
// the downstream service contracts, the identifiers, and the workflow-client
// logic (`processOrder` / `driverApp`), which only ever receive capabilities as
// parameters.  What stays in the shell (see Main.scala) is the part safe mode
// genuinely rejects: the activities each capture a ServiceInvocationCapability
// (WorkflowActivity.execute takes no capability argument, so the call target
// must be held in the instance), and the workflow + serverApp that reference
// those capturing activity types.
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

// ── Client helpers (start a workflow and await the outcome) ───────────────────
// Pure: the WorkflowCapability is received as a parameter and only used within
// this call, never stored, so capture checking is satisfied without @assumeSafe.

def processOrder(order: OrderRequest, timeout: FiniteDuration)(using
    WorkflowCapability,
    JsonCodec[OrderRequest],
    JsonCodec[OrderResult],
): ProcessOrderResult =
  val id = WorkflowCapability.start(OrderWorkflowName, order)
  WorkflowCapability.waitForCompletion(id, timeout) match
    case None       => ProcessOrderResult(order.orderId, timedOut = true, result = None)
    case Some(snap) =>
      ProcessOrderResult(
        order.orderId,
        timedOut = false,
        result = snap.serializedOutput.flatMap(_.decode[OrderResult].toOption),
      )

def driverApp(timeout: FiniteDuration)(using
    DaprCapability,
    JsonCodec[OrderRequest],
    JsonCodec[OrderResult],
): List[ProcessOrderResult] =
  DaprCapability.workflow:
    sampleOrders.map(processOrder(_, timeout))
