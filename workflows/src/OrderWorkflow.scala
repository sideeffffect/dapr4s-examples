package workflows

import dapr4s.*
import scala.concurrent.duration.*

// ── Safe Scala guarantee ────────────────────────────────────────────────────
// WorkflowContext is a capability provided to Workflow.run.  Each
// WorkflowContext.callActivity call returns a Task[O] that represents a
// durable, replayed-safe future value.  The Task cannot be awaited outside
// the workflow run method — it would have no context to replay against.
//
// The saga pattern (call activity → on failure, call compensation) is encoded
// as ordinary control flow inside run(), which the Dapr workflow runtime
// makes durable and deterministic via event-sourced replay.
// ─────────────────────────────────────────────────────────────────────────────

// ── Domain ────────────────────────────────────────────────────────────────────

case class OrderRequest(
  orderId:  String,
  item:     String,
  quantity: Int,
  budget:   Double,
)

case class ReservationResult(reserved: Boolean, reservationId: String)
case class PaymentResult(charged: Boolean, transactionId: String)
case class ShipmentResult(dispatched: Boolean, trackingId: String)
case class OrderResult(success: Boolean, message: String)

@scala.caps.assumeSafe
object OrderDomain:
  given upickle.default.ReadWriter[OrderRequest]      = upickle.default.macroRW
  given upickle.default.ReadWriter[ReservationResult] = upickle.default.macroRW
  given upickle.default.ReadWriter[PaymentResult]     = upickle.default.macroRW
  given upickle.default.ReadWriter[ShipmentResult]    = upickle.default.macroRW
  given upickle.default.ReadWriter[OrderResult]       = upickle.default.macroRW

import OrderDomain.given

// ── Activities ────────────────────────────────────────────────────────────────
// Each activity is a plain class.  The JsonCodec instances for I and O are
// picked up implicitly from OrderDomain at construction time.

class ReserveInventory extends WorkflowActivity[OrderRequest, ReservationResult]:
  def execute(req: OrderRequest): ReservationResult =
    // Simulate: items with quantity ≤ 5 can always be reserved.
    val ok = req.quantity <= 5
    println(s"[ReserveInventory] ${req.item} x${req.quantity}: ${if ok then "reserved" else "out of stock"}")
    ReservationResult(ok, if ok then s"RES-${req.orderId}" else "")

class CancelReservation extends WorkflowActivity[ReservationResult, Unit]:
  def execute(res: ReservationResult): Unit =
    println(s"[CancelReservation] cancelling ${res.reservationId}")

class ChargePayment extends WorkflowActivity[OrderRequest, PaymentResult]:
  def execute(req: OrderRequest): PaymentResult =
    // Simulate: budget < 10.0 is always declined.
    val ok = req.budget >= 10.0
    println(s"[ChargePayment] budget=${req.budget}: ${if ok then "charged" else "declined"}")
    PaymentResult(ok, if ok then s"TXN-${req.orderId}" else "")

class RefundPayment extends WorkflowActivity[PaymentResult, Unit]:
  def execute(payment: PaymentResult): Unit =
    println(s"[RefundPayment] refunding ${payment.transactionId}")

class DispatchShipment extends WorkflowActivity[OrderRequest, ShipmentResult]:
  def execute(req: OrderRequest): ShipmentResult =
    println(s"[DispatchShipment] dispatching ${req.item} to customer")
    ShipmentResult(dispatched = true, trackingId = s"TRK-${req.orderId}")

// ── Workflow (saga) ───────────────────────────────────────────────────────────

class OrderProcessingWorkflow extends Workflow:
  def run(using WorkflowContext): Unit =
    val order = WorkflowContext.getInput[OrderRequest].getOrElse:
      throw RuntimeException("no input")

    println(s"[workflow] processing order ${order.orderId}")

    // Step 1: reserve inventory
    val reservation = WorkflowContext.callActivity[ReserveInventory](order).await()
    if !reservation.reserved then
      WorkflowContext.complete(OrderResult(false, "out of stock"))
    else

    // Step 2: charge payment  (saga: compensate reservation on failure)
    val payment = WorkflowContext.callActivity[ChargePayment](order).await()
    if !payment.charged then
      WorkflowContext.callActivity[CancelReservation](reservation).await()
      WorkflowContext.complete(OrderResult(false, "payment declined"))
    else

    // Step 3: dispatch  (saga: refund payment + cancel reservation on failure)
    val shipment = WorkflowContext.callActivity[DispatchShipment](order).await()
    if !shipment.dispatched then
      WorkflowContext.callActivity[RefundPayment](payment).await()
      WorkflowContext.callActivity[CancelReservation](reservation).await()
      WorkflowContext.complete(OrderResult(false, "dispatch failed"))
    else

    WorkflowContext.complete(OrderResult(true, s"shipped: ${shipment.trackingId}"))

// ── Workflow server ───────────────────────────────────────────────────────────

@main def workflowApp(): Unit =
  val port   = sys.env.getOrElse("APP_PORT", "8087").toInt
  val config = DaprRuntimeConfig(appServer = AppServerConfig(port = DaprPort(port)))
  println(s"=== 07 workflows: OrderProcessingWorkflow server on port $port ===\n")

  DaprRuntime.serve(config):
    DaprApp(
      workflows  = List(new OrderProcessingWorkflow),
      activities = List(
        new ReserveInventory,
        new CancelReservation,
        new ChargePayment,
        new RefundPayment,
        new DispatchShipment,
      ),
    )
