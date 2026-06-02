package orderservice

import dapr4s.*
import scala.concurrent.duration.*

// ── Impure shell ──────────────────────────────────────────────────────────────
// Only the parts that safe mode genuinely rejects live here: each activity
// captures the ServiceInvocationCapability so it can call a downstream service
// (WorkflowActivity.execute takes no capability argument, so the target must be
// held in the instance), and the workflow + serverApp reference those capturing
// activity types.  Capturing a capability in a long-lived object is exactly what
// safe mode forbids, so these are written in the shell (compiled with
// -experimental, like the dapr4s library itself) using the @assumeSafe escape
// hatch.  The domain model and the workflow-client logic (processOrder /
// driverApp) are pure and live in the non-shell module — see App.scala.
// ─────────────────────────────────────────────────────────────────────────────

private def upickleCodec[T: upickle.default.ReadWriter]: JsonCodec[T] = new JsonCodec[T]:
  def encode(value: T): String = upickle.default.write(value)
  def decode(json: String | Null): Either[JsonDecodeException, T] =
    if json == null then Left(JsonDecodeException("null input"))
    else
      try Right(upickle.default.read[T](json))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

@scala.caps.assumeSafe
object Codecs:
  given JsonCodec[OrderRequest] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[OrderResult] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[ReserveRequest] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[ReservationResult] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[ReleaseRequest] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[ChargeRequest] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[PaymentResult] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[RefundRequest] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[ShipRequest] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[ShipmentResult] = upickleCodec(using upickle.default.macroRW)
  given JsonCodec[Unit] with
    def encode(value: Unit): String = "null"
    def decode(json: String | Null): Either[JsonDecodeException, Unit] = Right(())

import Codecs.given

// ── Activities — each performs one cross-service call ─────────────────────────
// WHY @assumeSafe: the activity captures the ServiceInvocationCapability acquired
// in serverApp's `invoker` scope.  That capability lives for the whole server
// lifetime (the same scope the activity instance lives in), so the capture is
// sound; the annotation erases the CC capture set so the instance can sit in the
// plain List[WorkflowActivity] held by DaprApp.activities.

@scala.caps.assumeSafe
class ReserveActivity(using ServiceInvocationCapability) extends WorkflowActivity[OrderRequest, ReservationResult]:
  def execute(o: OrderRequest): ReservationResult =
    ServiceInvocationCapability.invoke[ReserveRequest](
      InventoryService,
      MethodName("reserve"),
      ReserveRequest(o.orderId, o.sku, o.quantity),
    )[ReservationResult]

@scala.caps.assumeSafe
class ChargeActivity(using ServiceInvocationCapability) extends WorkflowActivity[OrderRequest, PaymentResult]:
  def execute(o: OrderRequest): PaymentResult =
    ServiceInvocationCapability.invoke[ChargeRequest](
      PaymentService,
      MethodName("charge"),
      ChargeRequest(o.orderId, o.amount),
    )[PaymentResult]

@scala.caps.assumeSafe
class DispatchActivity(using ServiceInvocationCapability) extends WorkflowActivity[OrderRequest, ShipmentResult]:
  def execute(o: OrderRequest): ShipmentResult =
    ServiceInvocationCapability.invoke[ShipRequest](
      ShippingService,
      MethodName("dispatch"),
      ShipRequest(o.orderId, o.address),
    )[ShipmentResult]

@scala.caps.assumeSafe
class ReleaseActivity(using ServiceInvocationCapability) extends WorkflowActivity[ReleaseRequest, Unit]:
  def execute(req: ReleaseRequest): Unit =
    ServiceInvocationCapability.invoke[ReleaseRequest](InventoryService, MethodName("release"), req)[Unit]

@scala.caps.assumeSafe
class RefundActivity(using ServiceInvocationCapability) extends WorkflowActivity[RefundRequest, Unit]:
  def execute(req: RefundRequest): Unit =
    ServiceInvocationCapability.invoke[RefundRequest](PaymentService, MethodName("refund"), req)[Unit]

// ── Workflow (saga orchestration — deterministic, no I/O of its own) ──────────

class OrderProcessingWorkflow extends Workflow:
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

// ── Apps ──────────────────────────────────────────────────────────────────────

def serverApp(timeout: FiniteDuration)(using DaprCapability): DaprApp =
  DaprCapability.invoker:
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
          InvocationRoute[OrderRequest, OrderResult](MethodName("submit-order")): order =>
            processOrder(order, timeout).result.getOrElse(OrderResult(false, "timed out")),
        ),
      )

private def daprConfigFromEnv(defaultAppPort: Int): DaprConfig =
  val appPort = sys.env.getOrElse("APP_PORT", defaultAppPort.toString).toInt
  val http = sys.env.getOrElse("DAPR_HTTP_PORT", "3500").toInt
  val grpc = sys.env.getOrElse("DAPR_GRPC_PORT", "50001").toInt
  DaprConfig(
    sidecar = SidecarConfig(
      httpEndpoint = java.net.URI.create(s"http://localhost:$http"),
      grpcEndpoint = java.net.URI.create(s"http://localhost:$grpc"),
      grpcTlsInsecure = false,
    ),
    appServer = AppServerConfig(port = DaprPort(appPort)),
  )

@main def orderServer(): Unit =
  val config = daprConfigFromEnv(defaultAppPort = 8091)
  println(s"=== 09 order-fulfillment: order-service (saga) on port ${config.appServer.port} ===\n")
  Dapr(config).serve:
    serverApp(timeout = 30.seconds)

@main def orderDriver(): Unit =
  println("=== 09 order-fulfillment: driver ===\n")
  Dapr(daprConfigFromEnv(defaultAppPort = 8091)).run:
    val results = driverApp(timeout = 30.seconds)
    results.foreach: r =>
      if r.timedOut then println(s"\nOrder ${r.orderId}: TIMED OUT")
      else
        val msg = r.result.map(x => s"success=${x.success}, message='${x.message}'").getOrElse("(no output)")
        println(s"\nOrder ${r.orderId}: $msg")
  println("\n[order-driver] done.")
