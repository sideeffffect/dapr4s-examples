package scangateway

import dapr4s.*

// ── 08 · Grafana-style scan pipeline — gateway service ────────────────────────
// The gateway is the ingestion edge of the vulnerability-scanning pipeline.
// It exposes a `submit` invocation route (callers POST an image to scan) and
// publishes each request onto the `scan-requested` topic for the worker fleet.
//
// In the real Grafana system the ingestion source is an SQS queue wired in via
// a Dapr input binding; here `seed` plays that role by publishing a fixed batch
// so the pipeline can be driven end-to-end without external infrastructure.
// ─────────────────────────────────────────────────────────────────────────────

val PubSubComponent = PubSubName("pubsub")
val ScanRequestedTopic = Topic("scan-requested")

case class ScanRequest(scanId: String, image: String, source: String)
case class SubmitResponse(accepted: Boolean, scanId: String)

def submit(req: ScanRequest)(using PubSubCapability, JsonCodec[ScanRequest]): SubmitResponse =
  PubSubCapability.publish(ScanRequestedTopic, req)
  SubmitResponse(accepted = true, req.scanId)

object GatewayApp:
  def apply()(using DaprCapability, JsonCodec[ScanRequest], JsonCodec[SubmitResponse]): DaprApp =
    DaprCapability.pubsub(PubSubComponent):
      DaprApp(invocations =
        List(
          InvocationRoute[ScanRequest, SubmitResponse](MethodName("submit"))(submit),
        ),
      )

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
  DaprCapability.pubsub(PubSubComponent):
    seedRequests.map: req =>
      PubSubCapability.publish(ScanRequestedTopic, req)
      s"${req.scanId} (${req.source})"
