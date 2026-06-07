package orders

import dapr4s.*
import dapr4s.derivation.*

// ── 14 · Observability — orders service (workflow + invocation + pub/sub) ──────
// The headline workload for the observability demo. One OrderWorkflow weaves
// together three building blocks so a single trace tree spans them all:
//   workflow → activities → (quotePrice invokes the pricing service) → publishes
//   an orderCompleted event → an audit subscription consumes it.
// Workflow instances are visible in the Diagrid dashboard (Redis actor store);
// the full span tree, Dapr metrics, and app/sidecar logs land in SigNoz.
//
// Activities receive a DaprCapability on every call, so the I/O activities
// (QuotePrice via service invocation, PublishOrderEvent via pub/sub) may do Dapr
// I/O; the rest are pure computations. All JsonCodecs are passed from the shell.
// ─────────────────────────────────────────────────────────────────────────────

case class OrderRequest(orderId: String, item: String, quantity: Int, budget: Double)
case class ReservationResult(reserved: Boolean, reservationId: String)
case class QuoteRequest(item: String, quantity: Int)
case class PriceQuote(item: String, unitPrice: Double, total: Double)
case class ChargeInput(order: OrderRequest, quote: PriceQuote)
case class PaymentResult(charged: Boolean, transactionId: String, amount: Double)
case class ShipmentResult(dispatched: Boolean, trackingId: String)
case class OrderEvent(orderId: String, item: String, outcome: String, amount: Double)
case class OrderResult(success: Boolean, message: String)

val PricingService = AppId("pricing")
val PubSubComponent = PubSubName("pubsub")

// Derived cross-service client + publisher (method name maps verbatim to the Dapr name).
trait PricingClient:
  def quote(req: QuoteRequest)(using
      ServiceInvocationCapability,
      JsonCodec[QuoteRequest],
      JsonCodec[PriceQuote],
  ): PriceQuote
def PricingClient(appId: AppId): PricingClient = ServiceInvocation.derive[PricingClient](appId)

trait OrderTopics:
  def orderCompleted(e: OrderEvent)(using PubSubCapability, JsonCodec[OrderEvent]): Unit
lazy val OrderTopics: OrderTopics = PubSub.derive[OrderTopics]

// ── Activities ────────────────────────────────────────────────────────────────
// A plain class of activity methods — no `extends WorkflowActivity`, no manual
// registration. `WorkflowActivities.derive[OrderActivities]` (in ServerApp) reifies
// one WorkflowActivity per method; `OrderActivityCalls` (below) is the typed caller
// the workflow uses. Each method receives a DaprCapability per call, so the I/O
// activities (quotePrice via service invocation, publishOrderEvent via pub/sub) may
// do Dapr I/O; the rest are pure computations.

class OrderActivities:
  def reserveInventory(req: OrderRequest)(using DaprCapability): ReservationResult =
    val ok = req.quantity <= 5
    ReservationResult(ok, if ok then s"RES-${req.orderId}" else "")

  // Calls the downstream pricing service over Dapr service invocation. This is the
  // cross-service hop that makes the distributed trace interesting.
  def quotePrice(req: OrderRequest)(using DaprCapability, JsonCodec[QuoteRequest], JsonCodec[PriceQuote]): PriceQuote =
    DaprCapability.invoker:
      PricingClient(PricingService).quote(QuoteRequest(req.item, req.quantity))

  def chargePayment(in: ChargeInput)(using DaprCapability): PaymentResult =
    val ok = in.order.budget >= in.quote.total
    PaymentResult(ok, if ok then s"TXN-${in.order.orderId}" else "", in.quote.total)

  def dispatchShipment(req: OrderRequest)(using DaprCapability): ShipmentResult =
    ShipmentResult(dispatched = true, trackingId = s"TRK-${req.orderId}")

  // Publishes the terminal order event to pub/sub. The audit subscription below
  // consumes it, adding a publish + deliver span to the trace.
  def publishOrderEvent(event: OrderEvent)(using DaprCapability, JsonCodec[OrderEvent]): Unit =
    DaprCapability.pubsub(PubSubComponent):
      OrderTopics.orderCompleted(event)

// Typed caller the workflow schedules activities through (derived from OrderActivities;
// each call forwards to WorkflowContext under the activity's name). The returned Task
// captures the per-call context, so it cannot escape `run`.
trait OrderActivityCalls:
  def reserveInventory(req: OrderRequest)(using ctx: WorkflowContext): Task[ReservationResult]^{ctx}
  def quotePrice(req: OrderRequest)(using ctx: WorkflowContext): Task[PriceQuote]^{ctx}
  def chargePayment(in: ChargeInput)(using ctx: WorkflowContext): Task[PaymentResult]^{ctx}
  def dispatchShipment(req: OrderRequest)(using ctx: WorkflowContext): Task[ShipmentResult]^{ctx}
  def publishOrderEvent(event: OrderEvent)(using ctx: WorkflowContext): Task[Unit]^{ctx}

// ── Workflow (saga) ───────────────────────────────────────────────────────────

class OrderWorkflow(using
    JsonCodec[OrderRequest],
    JsonCodec[ReservationResult],
    JsonCodec[QuoteRequest],
    JsonCodec[PriceQuote],
    JsonCodec[ChargeInput],
    JsonCodec[PaymentResult],
    JsonCodec[ShipmentResult],
    JsonCodec[OrderEvent],
    JsonCodec[OrderResult],
    JsonCodec[Unit],
) extends Workflow:
  def run(using WorkflowContext): Unit =
    val acts  = WorkflowActivityCalls.derive[OrderActivityCalls, OrderActivities]
    val order = WorkflowContext.getInput[OrderRequest].getOrElse(throw RuntimeException("no input"))

    val reservation = acts.reserveInventory(order).await()
    if !reservation.reserved then
      acts.publishOrderEvent(OrderEvent(order.orderId, order.item, "out-of-stock", 0.0)).await()
      WorkflowContext.complete(OrderResult(false, "out of stock"))
    else
      val quote   = acts.quotePrice(order).await()
      val payment = acts.chargePayment(ChargeInput(order, quote)).await()
      if !payment.charged then
        acts.publishOrderEvent(OrderEvent(order.orderId, order.item, "payment-declined", quote.total)).await()
        WorkflowContext.complete(OrderResult(false, "payment declined"))
      else
        val shipment = acts.dispatchShipment(order).await()
        acts.publishOrderEvent(OrderEvent(order.orderId, order.item, "shipped", payment.amount)).await()
        WorkflowContext.complete(OrderResult(true, s"shipped: ${shipment.trackingId}"))

// ── Audit subscription ──────────────────────────────────────────────────────────
// Consuming the event adds a pub/sub publish + deliver span to the trace. The
// handler stays pure (no println — that is @rejectSafe under safe mode); the
// log signal in the telemetry comes from the app and daprd stdout instead.

// Derived subscription: method name (camelCase, verbatim) → Topic.
object OrderSubscriptions:
  def orderCompleted(event: CloudEvent[OrderEvent])(using PubSubCapability, JsonCodec[OrderEvent]): SubscriptionResult =
    val _ = event.data
    SubscriptionResult.Success

// ── Server app ────────────────────────────────────────────────────────────────

object ServerApp:
  def apply()(using
      DaprCapability,
      JsonCodec[OrderRequest],
      JsonCodec[ReservationResult],
      JsonCodec[QuoteRequest],
      JsonCodec[PriceQuote],
      JsonCodec[ChargeInput],
      JsonCodec[PaymentResult],
      JsonCodec[ShipmentResult],
      JsonCodec[OrderEvent],
      JsonCodec[OrderResult],
      JsonCodec[Unit],
  ): DaprApp =
    DaprCapability.pubsub(PubSubComponent):
      DaprApp(
        workflows = List(new OrderWorkflow),
        activities = WorkflowActivities.derive[OrderActivities],
        subscriptions = Subscriptions.derive[OrderSubscriptions.type](PubSubComponent),
      )
