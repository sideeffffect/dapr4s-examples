package shippingservice

import dapr4s.*

// ── 09 · ZEISS-style order fulfillment — shipping service ─────────────────────
// A downstream microservice invoked by the order-service saga.  `dispatch`
// books a shipment (stubbed: fails when no address is supplied), which drives
// the saga's final compensation branch (refund + release).
// ─────────────────────────────────────────────────────────────────────────────

case class ShipRequest(orderId: String, address: String)
case class ShipmentResult(dispatched: Boolean, trackingId: String)

def dispatch(req: ShipRequest): ShipmentResult =
  val ok = req.address.nonEmpty
  ShipmentResult(dispatched = ok, if ok then s"TRK-${req.orderId}" else "")

object ShippingApp:
  def apply()(using JsonCodec[ShipRequest], JsonCodec[ShipmentResult]): DaprApp =
    DaprApp(invocations =
      List(
        InvocationRoute[ShipRequest, ShipmentResult](InvocationMethodName("dispatch"))(dispatch),
      ),
    )
