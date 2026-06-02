package workflows

import dapr4s.*
import scala.concurrent.duration.FiniteDuration

case class OrderRequest(orderId: String, item: String, quantity: Int, budget: Double)
case class ReservationResult(reserved: Boolean, reservationId: String)
case class PaymentResult(charged: Boolean, transactionId: String)
case class ShipmentResult(dispatched: Boolean, trackingId: String)
case class OrderResult(success: Boolean, message: String)
case class ProcessOrderResult(orderId: String, timedOut: Boolean, result: Option[OrderResult])

// ── Capture-checked pure module ───────────────────────────────────────────────
// WorkflowContext is a per-run ExclusiveCapability: Task[O] values from
// callActivity cannot be awaited outside the workflow run method.
// Activities receive a DaprCapability on every call (so they *may* do Dapr I/O),
// but these are pure computations that ignore it and return typed values.
// WorkflowActivity[I,O] requires JsonCodec[I] at construction; all codecs are
// captured as class members and are in scope wherever needed.
// Duration constants are passed from the shell so this module is clock-free.
// ─────────────────────────────────────────────────────────────────────────────

// ── Activities ────────────────────────────────────────────────────────────────

class ReserveInventory(using JsonCodec[OrderRequest], JsonCodec[ReservationResult])
    extends WorkflowActivity[OrderRequest, ReservationResult]:
  def execute(req: OrderRequest)(using DaprCapability): ReservationResult =
    val ok = req.quantity <= 5
    ReservationResult(ok, if ok then s"RES-${req.orderId}" else "")

class CancelReservation(using JsonCodec[ReservationResult], JsonCodec[Unit])
    extends WorkflowActivity[ReservationResult, Unit]:
  def execute(res: ReservationResult)(using DaprCapability): Unit = ()

class ChargePayment(using JsonCodec[OrderRequest], JsonCodec[PaymentResult])
    extends WorkflowActivity[OrderRequest, PaymentResult]:
  def execute(req: OrderRequest)(using DaprCapability): PaymentResult =
    val ok = req.budget >= 10.0
    PaymentResult(ok, if ok then s"TXN-${req.orderId}" else "")

class RefundPayment(using JsonCodec[PaymentResult], JsonCodec[Unit]) extends WorkflowActivity[PaymentResult, Unit]:
  def execute(payment: PaymentResult)(using DaprCapability): Unit = ()

class DispatchShipment(using JsonCodec[OrderRequest], JsonCodec[ShipmentResult])
    extends WorkflowActivity[OrderRequest, ShipmentResult]:
  def execute(req: OrderRequest)(using DaprCapability): ShipmentResult =
    ShipmentResult(dispatched = true, trackingId = s"TRK-${req.orderId}")

// ── Workflow (saga) ───────────────────────────────────────────────────────────

class OrderProcessingWorkflow(using
    JsonCodec[OrderRequest],
    JsonCodec[ReservationResult],
    JsonCodec[PaymentResult],
    JsonCodec[ShipmentResult],
    JsonCodec[OrderResult],
    JsonCodec[Unit],
) extends Workflow:
  def run(using WorkflowContext): Unit =
    val order = WorkflowContext
      .getInput[OrderRequest]
      .getOrElse:
        throw RuntimeException("no input")

    val reservation = WorkflowContext.callActivity[ReserveInventory](order).await()
    if !reservation.reserved then WorkflowContext.complete(OrderResult(false, "out of stock"))
    else
      val payment = WorkflowContext.callActivity[ChargePayment](order).await()
      if !payment.charged then
        WorkflowContext.callActivity[CancelReservation](reservation).await()
        WorkflowContext.complete(OrderResult(false, "payment declined"))
      else
        val shipment = WorkflowContext.callActivity[DispatchShipment](order).await()
        if !shipment.dispatched then
          WorkflowContext.callActivity[RefundPayment](payment).await()
          WorkflowContext.callActivity[CancelReservation](reservation).await()
          WorkflowContext.complete(OrderResult(false, "dispatch failed"))
        else WorkflowContext.complete(OrderResult(true, s"shipped: ${shipment.trackingId}"))

// ── Server app ────────────────────────────────────────────────────────────────

def serverApp()(using
    JsonCodec[OrderRequest],
    JsonCodec[ReservationResult],
    JsonCodec[PaymentResult],
    JsonCodec[ShipmentResult],
    JsonCodec[OrderResult],
    JsonCodec[Unit],
): DaprApp =
  DaprApp(
    workflows = List(new OrderProcessingWorkflow),
    activities = List(
      new ReserveInventory,
      new CancelReservation,
      new ChargePayment,
      new RefundPayment,
      new DispatchShipment,
    ),
  )

// ── Driver app ────────────────────────────────────────────────────────────────

def processOrder(
    order: OrderRequest,
    name: WorkflowName,
    timeout: FiniteDuration,
)(using DaprCapability, JsonCodec[OrderRequest], JsonCodec[OrderResult]): ProcessOrderResult =
  DaprCapability.workflow:
    val id = WorkflowCapability.start(name, order)
    val snapshot = WorkflowCapability.waitForCompletion(id, timeout)
    snapshot match
      case None       => ProcessOrderResult(order.orderId, timedOut = true, result = None)
      case Some(snap) =>
        val result = snap.serializedOutput.flatMap(_.decode[OrderResult].toOption)
        ProcessOrderResult(order.orderId, timedOut = false, result = result)

def driverApp(
    name: WorkflowName,
    timeout: FiniteDuration,
)(using DaprCapability, JsonCodec[OrderRequest], JsonCodec[OrderResult]): List[ProcessOrderResult] =
  List(
    processOrder(OrderRequest("ORD-001", item = "widget", quantity = 3, budget = 25.0), name, timeout),
    processOrder(OrderRequest("ORD-002", item = "gadget", quantity = 10, budget = 50.0), name, timeout),
    processOrder(OrderRequest("ORD-003", item = "gizmo", quantity = 1, budget = 5.0), name, timeout),
  )
