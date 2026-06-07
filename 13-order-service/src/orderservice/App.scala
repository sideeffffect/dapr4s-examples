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

// Derived workflow starter: the method name maps verbatim to the WorkflowName (the workflow's
// registered name is its class name, already PascalCase — so no `@name` override).
trait OrderWorkflows:
  def OrderProcessingWorkflow(input: OrderRequest)(using WorkflowCapability, JsonCodec[OrderRequest]): WorkflowInstanceId
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
  val id = OrderWorkflows.derive.OrderProcessingWorkflow(order)
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
// A plain class of activity methods — no `extends WorkflowActivity`, no manual
// registration. `WorkflowActivities.derive[OrderActivities]` (in ServerApp) reifies
// one WorkflowActivity per method; `OrderActivityCalls` (below) is the typed caller
// the saga uses. Pure under safe mode: each method receives the DaprCapability per
// call and uses it only within that call (never stored); the JsonCodecs the bodies
// need for their downstream calls are declared as `using` params and summoned at the
// derive site.

class OrderActivities:
  def reserve(o: OrderRequest)(using DaprCapability, JsonCodec[ReserveRequest], JsonCodec[ReservationResult])
      : ReservationResult =
    DaprCapability.invoker:
      InventoryClient.derive(InventoryService).reserve(ReserveRequest(o.orderId, o.sku, o.quantity))

  def charge(o: OrderRequest)(using DaprCapability, JsonCodec[ChargeRequest], JsonCodec[PaymentResult]): PaymentResult =
    DaprCapability.invoker:
      PaymentClient.derive(PaymentService).charge(ChargeRequest(o.orderId, o.amount))

  def dispatch(o: OrderRequest)(using DaprCapability, JsonCodec[ShipRequest], JsonCodec[ShipmentResult]): ShipmentResult =
    DaprCapability.invoker:
      ShippingClient.derive(ShippingService).dispatch(ShipRequest(o.orderId, o.address))

  def release(req: ReleaseRequest)(using DaprCapability, JsonCodec[ReleaseRequest], JsonCodec[Unit]): Unit =
    DaprCapability.invoker:
      InventoryClient.derive(InventoryService).release(req)

  def refund(req: RefundRequest)(using DaprCapability, JsonCodec[RefundRequest], JsonCodec[Unit]): Unit =
    DaprCapability.invoker:
      PaymentClient.derive(PaymentService).refund(req)

// Typed caller the saga schedules activities through (derived from OrderActivities;
// each call forwards to WorkflowContext under the activity's name).
trait OrderActivityCalls:
  def reserve(o: OrderRequest)(using ctx: WorkflowContext): Task[ReservationResult]^{ctx}
  def charge(o: OrderRequest)(using ctx: WorkflowContext): Task[PaymentResult]^{ctx}
  def dispatch(o: OrderRequest)(using ctx: WorkflowContext): Task[ShipmentResult]^{ctx}
  def release(req: ReleaseRequest)(using ctx: WorkflowContext): Task[Unit]^{ctx}
  def refund(req: RefundRequest)(using ctx: WorkflowContext): Task[Unit]^{ctx}
object OrderActivityCalls extends WorkflowActivityCalls.Derived[OrderActivityCalls, OrderActivities]

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
    val acts = OrderActivityCalls.derive
    val order = WorkflowContext
      .getInput[OrderRequest]
      .getOrElse:
        throw RuntimeException("no input")

    val reservation = acts.reserve(order).await()
    if !reservation.reserved then WorkflowContext.complete(OrderResult(false, "out of stock"))
    else
      val payment = acts.charge(order).await()
      if !payment.charged then
        acts.release(ReleaseRequest(reservation.reservationId, order.sku, order.quantity)).await()
        WorkflowContext.complete(OrderResult(false, "payment declined"))
      else
        val shipment = acts.dispatch(order).await()
        if !shipment.dispatched then
          acts.refund(RefundRequest(payment.transactionId, order.amount)).await()
          acts.release(ReleaseRequest(reservation.reservationId, order.sku, order.quantity)).await()
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
    // Routes close over `timeout`; InvocationRoutes.derive turns each method into an InvocationRoute,
    // summoning the WorkflowCapability/JsonCodecs the body needs at this derive site. The route name is
    // a backtick identifier (no `@name`): kebab-case here keeps the on-the-wire invocation path stable
    // for external HTTP callers, which is the documented fallback when a plain PascalCase name won't do.
    object OrderRoutes:
      def `submit-order`(order: OrderRequest)(using
          WorkflowCapability,
          JsonCodec[OrderRequest],
          JsonCodec[OrderResult],
      ): OrderResult =
        processOrder(order, timeout).result.getOrElse(OrderResult(false, "timed out"))

    DaprCapability.workflow:
      DaprApp(
        workflows = List(new OrderProcessingWorkflow),
        activities = WorkflowActivities.derive[OrderActivities],
        invocations = InvocationRoutes.derive[OrderRoutes.type],
      )
