package inventoryservice

import dapr4s.*
import dapr4s.derivation.*

// ── 09 · ZEISS-style order fulfillment — inventory service ────────────────────
// A downstream microservice invoked by the order-service saga.  Stock levels
// live in the state store, keyed per SKU.  `reserve` decrements (failing if
// insufficient); `release` is the compensating action that puts stock back.
// ─────────────────────────────────────────────────────────────────────────────

val StateStore = StateStoreName("statestore")
val DefaultStock = 10

case class ReserveRequest(orderId: String, sku: String, quantity: Int)
case class ReservationResult(reserved: Boolean, reservationId: String)
case class ReleaseRequest(reservationId: String, sku: String, quantity: Int)

def stockKey(sku: String): StateStoreKey = StateStoreKey(s"stock-$sku")

// Derived invocation routes: each method → an InvokeRoute. The handler bodies keep the
// explicit StateCapability calls — stock arithmetic over a *dynamic* key (stock-<sku>) has no
// derived form; only the route wiring is derived.
object InventoryRoutes:
  def reserve(req: ReserveRequest)(using StateCapability, JsonCodec[Int]): ReservationResult =
    val current = StateCapability.get[Int](stockKey(req.sku)).getOrElse(DefaultStock)
    if current >= req.quantity then
      StateCapability.save(stockKey(req.sku), current - req.quantity)
      ReservationResult(reserved = true, s"RES-${req.orderId}")
    else ReservationResult(reserved = false, "")

  def release(req: ReleaseRequest)(using StateCapability, JsonCodec[Int]): Unit =
    val current = StateCapability.get[Int](stockKey(req.sku)).getOrElse(DefaultStock)
    StateCapability.save(stockKey(req.sku), current + req.quantity)

object InventoryApp:
  def apply()(using
      DaprCapability,
      JsonCodec[ReserveRequest],
      JsonCodec[ReservationResult],
      JsonCodec[ReleaseRequest],
      JsonCodec[Int],
      JsonCodec[Unit],
  ): DaprApp =
    DaprCapability.state(StateStore):
      DaprApp(invokeRoutes = InvokeRoutes.derive[InventoryRoutes.type])
