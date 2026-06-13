package scangateway

import dapr4s.*
import dapr4s.derivation.*

// ── 08 · Grafana-style scan pipeline — gateway service ────────────────────────
// The gateway is the ingestion edge of the vulnerability-scanning pipeline.
// It exposes a `submit` invocation route (callers POST an image to scan) and
// publishes each request onto the `scanRequested` topic for the worker fleet.
//
// Fully derived: the publisher and the invocation route are both generated from
// trait/object descriptions — no reified `PublishCapability.publish`, no
// `InvokeRoute[...]` list.
// ─────────────────────────────────────────────────────────────────────────────

val PubSubComponent = PubSubName("pubsub")

case class ScanRequest(scanId: String, image: String, source: String)
case class SubmitResponse(accepted: Boolean, scanId: String)

// Derived publisher: method name (camelCase, verbatim) → Topic.
trait ScanTopics:
  def scanRequested(req: ScanRequest)(using PublishCapability, JsonCodec[ScanRequest]): Unit
lazy val ScanTopics: ScanTopics = Publish.derive[ScanTopics]

// Derived invocation routes: each method → an InvokeRoute (name → InvokeMethodName).
object GatewayRoutes:
  def submit(req: ScanRequest)(using PublishCapability, JsonCodec[ScanRequest]): SubmitResponse =
    ScanTopics.scanRequested(req)
    SubmitResponse(accepted = true, req.scanId)

object GatewayApp:
  def apply()(using DaprCapability, JsonCodec[ScanRequest], JsonCodec[SubmitResponse]): DaprApp =
    DaprCapability.publish(PubSubComponent):
      DaprApp(invokeRoutes = InvokeRoutes.derive[GatewayRoutes.type])

// ── Seed driver (stands in for the SQS input binding) ─────────────────────────
// Includes a duplicate scan-3 (the worker must dedup it) and a "flaky" source
// (the worker retries the first delivery, then succeeds).

def seedRequests: List[ScanRequest] =
  List(
    ScanRequest("scan-1", "ghcr.io/acme/api:1.2.0", "github"),
    ScanRequest("scan-2", "docker.io/library/nginx:1.27", "dockerhub"),
    ScanRequest("scan-3", "123456789.dkr.ecr.us-east-1.amazonaws.com/payments:sha-abc", "ecr"),
    ScanRequest("scan-3", "123456789.dkr.ecr.us-east-1.amazonaws.com/payments:sha-abc", "ecr"),
    ScanRequest("scan-4", "ghcr.io/acme/worker:2.0.0", "flaky"),
  )

def runSeed()(using DaprCapability, JsonCodec[ScanRequest]): List[String] =
  DaprCapability.publish(PubSubComponent):
    val topics = ScanTopics
    seedRequests.map: req =>
      topics.scanRequested(req)
      s"${req.scanId} (${req.source})"
