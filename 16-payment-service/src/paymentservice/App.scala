package paymentservice

import dapr4s.*
import dapr4s.derivation.*

// ── 09 · ZEISS-style order fulfillment — payment service ──────────────────────
// A downstream microservice invoked by the order-service saga.  `charge`
// authorises a payment (stubbed: succeeds up to a fixed limit); `refund` is the
// compensating action.  Stateless — each call is a pure decision.
// ─────────────────────────────────────────────────────────────────────────────

val PaymentLimit = 1000.0

case class ChargeRequest(orderId: String, amount: Double)
case class PaymentResult(charged: Boolean, transactionId: String)
case class RefundRequest(transactionId: String, amount: Double)

def charge(req: ChargeRequest): PaymentResult =
  val ok = req.amount > 0 && req.amount <= PaymentLimit
  PaymentResult(charged = ok, if ok then s"TXN-${req.orderId}" else "")

def refund(req: RefundRequest): Unit = ()

// Derived invocation routes: each method → an InvokeRoute (name → InvokeMethodName).
object PaymentRoutes:
  def charge(req: ChargeRequest): PaymentResult = paymentservice.charge(req)
  def refund(req: RefundRequest): Unit = paymentservice.refund(req)

object PaymentApp:
  def apply()(using
      JsonCodec[ChargeRequest],
      JsonCodec[PaymentResult],
      JsonCodec[RefundRequest],
      JsonCodec[Unit],
  ): DaprApp =
    DaprApp(invokeRoutes = InvokeRoutes.derive[PaymentRoutes.type])
