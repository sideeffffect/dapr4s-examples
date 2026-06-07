# Example 14 — Observability & the Diagrid Dashboard

Status: design agreed via interview (2026-06-06). Implementation in progress.

## Goal

Showcase, in one runnable example:

1. **The Diagrid Dev Dashboard** inspecting live Dapr **workflow** state (read from
   the Redis actor state store).
2. **Full OpenTelemetry observability** — traces **+** metrics **+** logs — in a
   **single integrated FOSS backend**: **SigNoz** (OTel-native, ClickHouse-backed,
   Apache-2.0). Chosen over gluing Tempo/Loki/Mimir+Grafana together.

It runs as an **E2E test in the normal suite** with **rigorous** assertions, and has
a **`PresentationMode` boolean** that keeps the whole stack up **forever** with
**fixed, memorable dashboard URLs** so the dashboards can be demoed live without the
test tearing down mid-talk.

## Decisions (from interview)

| Question | Decision |
|---|---|
| Observability stack | **SigNoz** (single integrated backend), fronted by an OTel Collector funnel |
| Workload | **New dedicated multi-service app** (example 14) |
| CI assertion rigor | **Rigorous** — prove all three signals actually flow, + workflow visible |
| Presentation ports | **Fixed well-known host ports + printed URLs** |

## Workload (the app)

Two dapr4s services (pure core + impure shell each), continuously driven:

- **`14-orders`** — workflow service. `OrderWorkflow` orchestrates activities:
  `ReserveInventory` → an activity that **invokes the pricing service** (service
  invocation) → `ChargePayment` → `DispatchShipment`. On completion the app
  **publishes** an `OrderCompleted` event to pub/sub and **subscribes** to that
  topic with an audit handler. Inputs vary so outcomes split across
  shipped / out-of-stock / payment-declined → varied span trees.
- **`14-pricing`** — service-invocation callee. A `quote` route returns a price for
  an item; injects occasional small latency / a failure branch so traces and error
  metrics are interesting.

Building blocks exercised: **Workflow, Activities, Service Invocation, Pub/Sub, State
(actor store), Observability**. Workflow instances show up in the Diagrid dashboard;
the full trace tree (workflow → activity → cross-service invoke → pubsub publish →
audit deliver) shows up in SigNoz.

## Telemetry topology

```
orders app ─┐                          ┌─ traces (OTLP 4317)
            ├─ daprd (orders) ─────────┤
pricing app ┘   :9090 /metrics         │
            ┌─ daprd (pricing) ────────┤
            │   :9090 /metrics         │
            ▼                          ▼
   our OTel Collector (contrib)  ── OTLP ──▶  SigNoz otel-collector ──▶ ClickHouse ──▶ SigNoz UI
     receivers: otlp(4317/4318),                                                        (:8080)
                prometheus(scrape both :9090),
                filelog(/var/lib/docker/containers)
     exporters: otlp → signoz-otel-collector:4317 (tls insecure)
     telemetry: own metrics on :8888  ◀── E2E assertions scrape this

Redis (actor state store) ──▶ Diagrid dashboard (:8080)  [workflow inspection]
```

Why an OTel Collector in front: Dapr pushes **traces** via OTLP but exposes
**metrics** only as Prometheus scrape and **logs** only on stdout. The Collector is
OTel's own pipeline (not a competing backend) and is the single funnel that unifies
all three into SigNoz — and its pipeline counters give us a robust assertion hook.

## Dapr tracing configuration

A Dapr `Configuration` resource, mounted and selected per sidecar via `daprd -config`:

```yaml
apiVersion: dapr.io/v1alpha1
kind: Configuration
metadata: { name: obsconfig }
spec:
  tracing:
    samplingRate: "1"
    otel:
      endpointAddress: "otel-collector:4317"
      isSecure: false
      protocol: grpc
```

Metrics are on by default (daprd `:9090/metrics`).

## Containers (one file: `e2e/docker/docker-compose.14-observability.yml`, network `obs-net`)

Dapr infra: `redis`, `placement`, `scheduler`.
Apps: `orders` (+ `orders-dapr` sidecar, shared netns), `pricing` (+ `pricing-dapr`).
Funnel: `otel-collector` (`otel/opentelemetry-collector-contrib`).
SigNoz (vendored, pinned): `init-clickhouse`, `zookeeper-1`, `clickhouse`, `signoz`,
`signoz-otel-collector`, `signoz-telemetrystore-migrator`.
Inspector: `diagrid-dashboard`.

Pinned images: `signoz/signoz:v0.127.0`, `signoz/signoz-otel-collector:v0.144.5`,
`clickhouse/clickhouse-server:25.5.6`, `signoz/zookeeper:3.7.1`,
`otel/opentelemetry-collector-contrib:0.111.0`, `ghcr.io/diagridio/diagrid-dashboard:0.0.3`.

## Fixed-vs-ephemeral host ports (one static compose)

Each host-facing port is published as `"${VAR:-}:<container>"`. Empty var → Docker
publishes an **ephemeral** host port (CI; read via `getServicePort`). Set var →
**fixed** host port (presentation). Presentation map:

| Service | Container port | Fixed host port (presentation) |
|---|---|---|
| SigNoz UI | 8080 | **3301** |
| Diagrid dashboard | 8080 | **8080** |
| orders app | 8080 | 8088 |
| orders daprd HTTP | 3500 | 3500 |
| pricing app | 8080 | 8089 |
| our collector telemetry | 8888 | 8888 |

(Resolves the SigNoz↔Diagrid 8080 collision at the host layer.)

## E2E test: `Example14ObservabilityTest`

`object Example14Observability { val PresentationMode: Boolean = false }` — the single
flag. Fixture `ObservabilityInfra` boots the compose, waits on both sidecars
(`dapr initialized. Status: Running.`), `clickhouse` healthy, `signoz` health, and the
collector. `generateLoad(n)` starts order workflows with varied inputs via the orders
sidecar and polls some to completion.

### Rigorous assertions (PresentationMode = false)
1. At least one `OrderWorkflow` reaches `COMPLETED` (Dapr workflow API).
2. **Metrics at source**: scrape `orders-dapr:9090/metrics` → contains `dapr_` metrics.
3. **All three signals reached SigNoz**: poll our collector `:8888/metrics` until
   - `otelcol_exporter_sent_spans` > 0
   - `otelcol_exporter_sent_metric_points` > 0
   - `otelcol_exporter_sent_log_records` > 0
   (deterministic, auth-free proof the funnel exported traces+metrics+logs to SigNoz).
4. **Backend healthy**: `GET signoz:8080/api/v1/health` == 200.
5. **Inspector reachable**: `GET diagrid:8080/` == 200 (workflow visibility best-effort).

Why collector counters instead of SigNoz's query API: SigNoz self-host requires an
admin **service-account API key** (register→login) and a v5 query body we could not
confirm verbatim — too brittle for must-stay-green CI. The collector's standard
pipeline counters prove the same end-to-end fact without auth or schema coupling.

### Presentation mode (PresentationMode = true)
- Fixture sets the fixed host-port env vars before boot.
- `munitTimeout = Duration.Inf`; never tears down.
- Prints a banner with clickable URLs (SigNoz `http://localhost:3301`, Diagrid
  `http://localhost:8080`) and where to look (Traces / Metrics / Logs / Workflows).
- Loops forever issuing a gentle trickle of orders so the dashboards stay live.

## Files

- `14-orders/src/orders/App.scala`, `14-orders-shell/src/orders/Main.scala`
- `14-pricing/src/pricing/App.scala`, `14-pricing-shell/src/pricing/Main.scala`
- `components/14-observability/{obsconfig.yaml, diagrid-state.yaml}`
- `e2e/docker/docker-compose.14-observability.yml`
- `e2e/docker/obs/otel-collector-config.yaml` (ours)
- `e2e/docker/signoz/**` (vendored verbatim: `clickhouse/{config,users,custom-function,cluster}.xml`,
  `signoz/otel-collector-opamp-config.yaml`, `signoz-otel-collector-config.yaml`)
- `e2e/src/e2e/Example14ObservabilityTest.scala` + `ObservabilityInfra` (in `DaprCompose.scala`)
- `build.mill` (register modules + e2e jars)
- docs: `AGENTS.md`, `README.md`, `SLIDES.md`; wiki `dapr/diagrid-dev-dashboard.md` + a new observability article.

## Implementation notes (surfaced by the live E2E run)

Three fixes were needed to get all three signals into SigNoz; all are in place and the
E2E passes:

1. **Collector runs as root** (`user: "0:0"`) so the `filelog` receiver can read the
   root-owned `/var/lib/docker/containers/*.log`.
2. **SigNoz's collector runs without OpAMP** — just its static `--config` (no
   `--manager-config`/`--copy-path`). SigNoz normally has the server push the
   collector's pipeline over OpAMP; that handshake is brittle (server/collector
   version coupling) and, when it fails, the collector never opens its OTLP `:4317`
   listener (→ `connection refused`, empty dashboards). The vendored config is a
   complete `otlp → ClickHouse` pipeline, so running it directly is self-contained;
   the UI reads telemetry straight from ClickHouse.
3. **The fixture gates on `signoz-otel-collector` logging "Everything is ready"**
   (not just the `signoz` server health, which passes independently) before driving
   load, so the funnel's exporter isn't retrying into a closed port.

Verified: `./mill e2e.testForked '*observability*'` → green; the funnel's
`otelcol_exporter_sent_{spans,metric_points,log_records}` are all > 0.

## CI (GitHub Actions)

- **Image cache**: the `e2e` workflow saves/loads a single `docker save` tarball of
  the full image set, keyed on a hash of that set — fetches each image from a
  registry at most once per set and dodges Docker Hub's anonymous rate limit. An
  optional Docker Hub login activates only if `DOCKERHUB_USERNAME`/`DOCKERHUB_TOKEN`
  secrets are set.
- **Parallel split**: the `e2e` job is a 2-leg matrix sharing the image cache —
  `core` runs all suites with `E2E_SKIP_OBSERVABILITY=1` (this suite self-skips via
  `munitIgnore`), and `observability` runs only this suite. The heavy SigNoz stack
  thus runs on its own runner in parallel with the 13 lighter suites instead of
  serially behind the global suite semaphore.

## Caveats / risks

- **Heavy**: ~13 containers; ClickHouse needs **≥4 GB** Docker memory.
- `init-clickhouse` **downloads a binary from GitHub at startup** → the host needs
  outbound internet (CI already pulls images, so OK).
- `filelog` reads `/var/lib/docker/containers/*/*.log` — Linux/Docker-specific (fine
  for this presenter's Linux host and Linux CI; would need adjustment on Docker Desktop VMs).
- The global suite semaphore already serializes suites, so this heavy stack never
  overlaps another. Generous startup timeouts (5 min).
