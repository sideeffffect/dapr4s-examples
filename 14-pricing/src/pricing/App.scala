package pricing

import dapr4s.*
import dapr4s.derivation.*

// ── 14 · Observability — pricing service (service-invocation callee) ───────────
// A tiny downstream service the orders workflow invokes for a price quote. Each
// invocation produces a service-invocation span (orders-dapr → pricing-dapr →
// pricing app) in the distributed trace, plus Dapr HTTP metrics on both sidecars.
// Kept pure: the price is a deterministic function of the request, so no clock or
// I/O is needed here (real network latency already gives the traces variety).
// ─────────────────────────────────────────────────────────────────────────────

case class QuoteRequest(item: String, quantity: Int)
case class PriceQuote(item: String, unitPrice: Double, total: Double)

// A stable per-item base price derived from the item name (no state, no clock).
private def basePrice(item: String): Double =
  val h = math.abs(item.hashCode % 900) + 100 // 100..999 cents
  h / 100.0

// Derived invocation route: `quote` → InvokeMethodName("quote").
object PricingRoutes:
  def quote(req: QuoteRequest): PriceQuote =
    val unit = basePrice(req.item)
    PriceQuote(req.item, unit, unit * req.quantity)

object PricingApp:
  def apply()(using
      DaprCapability,
      JsonCodec[QuoteRequest],
      JsonCodec[PriceQuote],
  ): DaprApp =
    DaprApp(invokeRoutes = InvokeRoutes.derive[PricingRoutes.type])
