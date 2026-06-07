package orderservice

import dapr4s.*
import dapr4s.derivation.*
import scala.concurrent.duration.*

// ── 09 · ZEISS-style order fulfillment — order service ────────────────────────
// This whole module is pure (capture-checked "safe mode").  Because
// WorkflowActivity.execute now receives a DaprCapability on every call, the
// activities perform their cross-service invocations without capturing a
// capability in a field — so the activities, the saga workflow, and ServerApp
// all live here rather than in the shell.  Capabilities are only ever used
// within the call that receives them; the JsonCodecs activities/workflows need
// are plain values threaded in as `using` parameters.  The only thing left in
// the shell (see Main.scala) is what safe mode genuinely rejects: the upickle
// codec derivations and the `@main` entry points (which do console I/O).
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

// Derived cross-service clients: each method name maps verbatim to the callee's
// InvocationMethodName. The activities call these instead of ServiceInvocationCapability.invoke.
trait InventoryClient:
  def reserve(req: ReserveRequest)(using
      ServiceInvocationCapability,
      JsonCodec[ReserveRequest],
      JsonCodec[ReservationResult],
  ): ReservationResult
  def release(req: ReleaseRequest)(using ServiceInvocationCapability, JsonCodec[ReleaseRequest], JsonCodec[Unit]): Unit
object InventoryClient extends ServiceInvocation.Derived[InventoryClient]

trait PaymentClient:
  def charge(req: ChargeRequest)(using
      ServiceInvocationCapability,
      JsonCodec[ChargeRequest],
      JsonCodec[PaymentResult],
  ): PaymentResult
  def refund(req: RefundRequest)(using ServiceInvocationCapability, JsonCodec[RefundRequest], JsonCodec[Unit]): Unit
object PaymentClient extends ServiceInvocation.Derived[PaymentClient]

trait ShippingClient:
  def dispatch(req: ShipRequest)(using
      ServiceInvocationCapability,
      JsonCodec[ShipRequest],
      JsonCodec[ShipmentResult],
  ): ShipmentResult
object ShippingClient extends ServiceInvocation.Derived[ShippingClient]

// Derived workflow starter: @name → WorkflowName (the workflow's registered name).
trait OrderWorkflows:
  @name("OrderProcessingWorkflow")
  def start(input: OrderRequest)(using WorkflowCapability, JsonCodec[OrderRequest]): WorkflowInstanceId
object OrderWorkflows extends Workflow.Derived[OrderWorkflows]

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
  val id = OrderWorkflows.derive.start(order)
  id.waitForCompletion(timeout) match
    case None       => ProcessOrderResult(order.orderId, timedOut = true, result = None)
    case Some(snap) =>
      ProcessOrderResult(
        order.orderId,
        timedOut = false,
        result = snap.serializedOutput.flatMap(_.decode[OrderResult].toOption),
      )

object DriverApp:
  def apply(timeout: FiniteDuration)(using
      DaprCapability,
      JsonCodec[OrderRequest],
      JsonCodec[OrderResult],
  ): List[ProcessOrderResult] =
    DaprCapability.workflow:
      sampleOrders.map(processOrder(_, timeout))

// ── Activities — each performs one cross-service call ─────────────────────────
// Pure under safe mode: `execute` receives the DaprCapability per call and uses
// it only within that call (never stored), and the JsonCodecs are plain values
// captured at construction.  No @assumeSafe — contrast with capturing a
// ServiceInvocationCapability in a field, which safe mode forbids.

class ReserveActivity(using JsonCodec[OrderRequest], JsonCodec[ReservationResult], JsonCodec[ReserveRequest])
    extends WorkflowActivity[OrderRequest, ReservationResult]:
  def execute(o: OrderRequest)(using DaprCapability): ReservationResult =
    DaprCapability.invoker:
      InventoryClient.derive(InventoryService).reserve(ReserveRequest(o.orderId, o.sku, o.quantity))

class ChargeActivity(using JsonCodec[OrderRequest], JsonCodec[PaymentResult], JsonCodec[ChargeRequest])
    extends WorkflowActivity[OrderRequest, PaymentResult]:
  def execute(o: OrderRequest)(using DaprCapability): PaymentResult =
    DaprCapability.invoker:
      PaymentClient.derive(PaymentService).charge(ChargeRequest(o.orderId, o.amount))

class DispatchActivity(using JsonCodec[OrderRequest], JsonCodec[ShipmentResult], JsonCodec[ShipRequest])
    extends WorkflowActivity[OrderRequest, ShipmentResult]:
  def execute(o: OrderRequest)(using DaprCapability): ShipmentResult =
    DaprCapability.invoker:
      ShippingClient.derive(ShippingService).dispatch(ShipRequest(o.orderId, o.address))

class ReleaseActivity(using JsonCodec[ReleaseRequest], JsonCodec[Unit]) extends WorkflowActivity[ReleaseRequest, Unit]:
  def execute(req: ReleaseRequest)(using DaprCapability): Unit =
    DaprCapability.invoker:
      InventoryClient.derive(InventoryService).release(req)

class RefundActivity(using JsonCodec[RefundRequest], JsonCodec[Unit]) extends WorkflowActivity[RefundRequest, Unit]:
  def execute(req: RefundRequest)(using DaprCapability): Unit =
    DaprCapability.invoker:
      PaymentClient.derive(PaymentService).refund(req)

// ── Workflow (saga orchestration — deterministic, no I/O of its own) ──────────

class OrderProcessingWorkflow(using
    JsonCodec[OrderRequest],
    JsonCodec[ReservationResult],
    JsonCodec[PaymentResult],
    JsonCodec[ShipmentResult],
    JsonCodec[OrderResult],
    JsonCodec[ReleaseRequest],
    JsonCodec[RefundRequest],
    JsonCodec[Unit],
) extends Workflow:
  def run(using WorkflowContext): Unit =
    val order = WorkflowContext
      .getInput[OrderRequest]
      .getOrElse:
        throw RuntimeException("no input")

    val reservation = WorkflowContext.callActivity[ReserveActivity](order).await()
    if !reservation.reserved then WorkflowContext.complete(OrderResult(false, "out of stock"))
    else
      val payment = WorkflowContext.callActivity[ChargeActivity](order).await()
      if !payment.charged then
        WorkflowContext
          .callActivity[ReleaseActivity](ReleaseRequest(reservation.reservationId, order.sku, order.quantity))
          .await()
        WorkflowContext.complete(OrderResult(false, "payment declined"))
      else
        val shipment = WorkflowContext.callActivity[DispatchActivity](order).await()
        if !shipment.dispatched then
          WorkflowContext.callActivity[RefundActivity](RefundRequest(payment.transactionId, order.amount)).await()
          WorkflowContext
            .callActivity[ReleaseActivity](ReleaseRequest(reservation.reservationId, order.sku, order.quantity))
            .await()
          WorkflowContext.complete(OrderResult(false, "dispatch failed"))
        else WorkflowContext.complete(OrderResult(true, s"shipped: ${shipment.trackingId}"))

// ── Server app ────────────────────────────────────────────────────────────────

object ServerApp:
  def apply(timeout: FiniteDuration)(using
      DaprCapability,
      JsonCodec[OrderRequest],
      JsonCodec[OrderResult],
      JsonCodec[ReservationResult],
      JsonCodec[PaymentResult],
      JsonCodec[ShipmentResult],
      JsonCodec[ReserveRequest],
      JsonCodec[ChargeRequest],
      JsonCodec[ShipRequest],
      JsonCodec[ReleaseRequest],
      JsonCodec[RefundRequest],
      JsonCodec[Unit],
  ): DaprApp =
    DaprCapability.workflow:
      DaprApp(
        workflows = List(new OrderProcessingWorkflow),
        activities = List(
          new ReserveActivity,
          new ChargeActivity,
          new DispatchActivity,
          new ReleaseActivity,
          new RefundActivity,
        ),
        invocations = List(
          InvocationRoute[OrderRequest, OrderResult](InvocationMethodName("submit-order")): order =>
            processOrder(order, timeout).result.getOrElse(OrderResult(false, "timed out")),
        ),
      )
