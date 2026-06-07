package orders

import dapr4s.*
import dapr4s.derivation.*

// ── 14 · Observability — orders service (workflow + invocation + pub/sub) ──────
// The headline workload for the observability demo. One OrderWorkflow weaves
// together three building blocks so a single trace tree spans them all:
//   workflow → activities → (QuotePrice invokes the pricing service) → publishes
//   an order-completed event → an audit subscription consumes it.
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

// Derived cross-service client + publisher (method name / @name → Dapr name).
trait PricingClient:
  def quote(req: QuoteRequest)(using
      ServiceInvocationCapability,
      JsonCodec[QuoteRequest],
      JsonCodec[PriceQuote],
  ): PriceQuote
object PricingClient extends ServiceInvocation.Derived[PricingClient]

trait OrderTopics:
  @name("order-completed") def orderCompleted(e: OrderEvent)(using PubSubCapability, JsonCodec[OrderEvent]): Unit
object OrderTopics extends PubSub.Derived[OrderTopics]

// ── Activities ────────────────────────────────────────────────────────────────

class ReserveInventory(using JsonCodec[OrderRequest], JsonCodec[ReservationResult])
    extends WorkflowActivity[OrderRequest, ReservationResult]:
  def execute(req: OrderRequest)(using DaprCapability): ReservationResult =
    val ok = req.quantity <= 5
    ReservationResult(ok, if ok then s"RES-${req.orderId}" else "")

// Calls the downstream pricing service over Dapr service invocation. This is the
// cross-service hop that makes the distributed trace interesting.
class QuotePrice(using
    JsonCodec[OrderRequest],
    JsonCodec[QuoteRequest],
    JsonCodec[PriceQuote],
) extends WorkflowActivity[OrderRequest, PriceQuote]:
  def execute(req: OrderRequest)(using DaprCapability): PriceQuote =
    DaprCapability.invoker:
      PricingClient.derive(PricingService).quote(QuoteRequest(req.item, req.quantity))

class ChargePayment(using JsonCodec[ChargeInput], JsonCodec[PaymentResult])
    extends WorkflowActivity[ChargeInput, PaymentResult]:
  def execute(in: ChargeInput)(using DaprCapability): PaymentResult =
    val ok = in.order.budget >= in.quote.total
    PaymentResult(ok, if ok then s"TXN-${in.order.orderId}" else "", in.quote.total)

class DispatchShipment(using JsonCodec[OrderRequest], JsonCodec[ShipmentResult])
    extends WorkflowActivity[OrderRequest, ShipmentResult]:
  def execute(req: OrderRequest)(using DaprCapability): ShipmentResult =
    ShipmentResult(dispatched = true, trackingId = s"TRK-${req.orderId}")

// Publishes the terminal order event to pub/sub. The audit subscription below
// consumes it, adding a publish + deliver span to the trace.
class PublishOrderEvent(using JsonCodec[OrderEvent], JsonCodec[Unit]) extends WorkflowActivity[OrderEvent, Unit]:
  def execute(event: OrderEvent)(using DaprCapability): Unit =
    DaprCapability.pubsub(PubSubComponent):
      OrderTopics.derive.orderCompleted(event)

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
    val order = WorkflowContext.getInput[OrderRequest].getOrElse(throw RuntimeException("no input"))

    val reservation = WorkflowContext.callActivity[ReserveInventory](order).await()
    if !reservation.reserved then
      WorkflowContext
        .callActivity[PublishOrderEvent](OrderEvent(order.orderId, order.item, "out-of-stock", 0.0))
        .await()
      WorkflowContext.complete(OrderResult(false, "out of stock"))
    else
      val quote = WorkflowContext.callActivity[QuotePrice](order).await()
      val payment = WorkflowContext.callActivity[ChargePayment](ChargeInput(order, quote)).await()
      if !payment.charged then
        WorkflowContext
          .callActivity[PublishOrderEvent](OrderEvent(order.orderId, order.item, "payment-declined", quote.total))
          .await()
        WorkflowContext.complete(OrderResult(false, "payment declined"))
      else
        val shipment = WorkflowContext.callActivity[DispatchShipment](order).await()
        WorkflowContext
          .callActivity[PublishOrderEvent](OrderEvent(order.orderId, order.item, "shipped", payment.amount))
          .await()
        WorkflowContext.complete(OrderResult(true, s"shipped: ${shipment.trackingId}"))

// ── Audit subscription ──────────────────────────────────────────────────────────
// Consuming the event adds a pub/sub publish + deliver span to the trace. The
// handler stays pure (no println — that is @rejectSafe under safe mode); the
// log signal in the telemetry comes from the app and daprd stdout instead.

// Derived subscription: method (@name) → Topic.
object OrderSubscriptions:
  @name("order-completed")
  def onOrderEvent(event: CloudEvent[OrderEvent])(using PubSubCapability, JsonCodec[OrderEvent]): SubscriptionResult =
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
        activities = List(
          new ReserveInventory,
          new QuotePrice,
          new ChargePayment,
          new DispatchShipment,
          new PublishOrderEvent,
        ),
        subscriptions = Subscriptions.derive[OrderSubscriptions.type](PubSubComponent),
      )
