package shippingservice

import dapr4s.*
import dapr4s.derivation.*

// ── 09 · ZEISS-style order fulfillment — shipping service ─────────────────────
// A downstream microservice invoked by the order-service saga.  `dispatch`
// books a shipment (stubbed: fails when no address is supplied), which drives
// the saga's final compensation branch (refund + release).
// ─────────────────────────────────────────────────────────────────────────────

case class ShipRequest(orderId: String, address: String)
case class ShipmentResult(dispatched: Boolean, trackingId: String)

// Derived invocation route: `dispatch` → InvokeMethodName("dispatch").
object ShippingRoutes:
  def dispatch(req: ShipRequest): ShipmentResult =
    val ok = req.address.nonEmpty
    ShipmentResult(dispatched = ok, if ok then s"TRK-${req.orderId}" else "")

object ShippingApp:
  def apply()(using JsonCodec[ShipRequest], JsonCodec[ShipmentResult]): DaprApp =
    DaprApp(invokeRoutes = InvokeRoutes.derive[ShippingRoutes.type])
