# dapr4s-examples

Self-contained, runnable examples for [dapr4s](https://github.com/sideeffffect/dapr4s)
— a Scala 3 wrapper around [Dapr](https://dapr.io) that models Dapr effects as
**capture-checked capabilities** (Safe Scala). Each example shows one Dapr building
block; the compiler statically enforces that a capability cannot be used outside the
scope that owns it.

## The pure / shell split

Every example is two modules:

- **pure** (`NN-name`) — compiled with `-language:experimental.safe`. Holds the
  domain model and logic. Capabilities are received as parameters and never captured.
- **shell** (`NN-name-shell`) — compiled with `-experimental` only. Holds the
  `@main` entry point, JSON codecs, and the few spots that must capture a capability
  in a long-lived object (guarded by `@scala.caps.assumeSafe`).

## Examples

| # | Topic |
|---|---|
| 01 | State store |
| 02 | Secrets & configuration |
| 03 | Pub/sub |
| 04 | Service invocation |
| 05 | Distributed lock |
| 06 | Actors |
| 07 | Workflows |
| 08 | Cryptography (encrypt / decrypt via crypto.dapr.localstorage) |
| 09 | Jobs (schedule a job; the sidecar fires it back to a JobRoute) |
| 10 | Conversation / LLM (alpha1 converse + alpha2 converseAlpha2, echo component) |
| 11 | Bindings (output: HTTP to jsonplaceholder + Kafka; input: a BindingRoute triggered by Kafka) |
| 12 | Grafana-style scan pipeline (fan-out, dedup, retry, dead-letter queue) — *real-world case study* |
| 13 | Order-fulfillment saga across inventory / payment / shipping services — *real-world case study* |
| 14 | Observability & the Diagrid dashboard (OrderWorkflow → service invocation + pub/sub; traces/metrics/logs into SigNoz; workflow state in the Diagrid dashboard) |
| 15 | Grafana-style scan pipeline **on Scala.js** — the JS twin of 12 |
| 16 | Order-fulfillment saga **on Scala.js** — the JS twin of 13 |
| 17 | Telemetry workload (OrderWorkflow → invocation + pub/sub) **on Scala.js** — the JS twin of 14's core |

### Scala.js examples (15 / 16 / 17)

dapr4s cross-compiles to Scala.js (the `dapr4s_sjs1_3` artifact, with the Dapr JS SDK
facades embedded), so the same `App.scala` runs unchanged on Node. Examples 15–17
re-implement the real-world case studies 12–14 on Scala.js to prove the published JS
artifact end to end: each service is the **same pure module** as its JVM twin, with only
the shell's entry point differing (a single `js.async { Dapr(config).serve … }` at the
program edge — `serve` suspends the WebAssembly stack via JSPI instead of parking a
virtual thread). They link with the experimental Wasm backend (`scalaJSExperimentalUseWebAssembly`,
ES modules, ES2017) and run as Node 25 processes (JSPI is on by default there), each paired
with its own daprd sidecar — exactly like the JVM examples, but `node main.js` instead of
`java -cp out.jar`.

```bash
# Node 25 + the runtime npm deps the bundles import (@dapr/dapr, express):
(cd e2e/js && npm ci)
# Link the Wasm bundles, then run the three JS suites:
./mill 15-scan-gateway-shell.fastLinkJS 16-order-service-shell.fastLinkJS 17-orders-shell.fastLinkJS  # …and the rest
./mill e2e.testForked '*Js*'
```

## Build & test

Built with [Mill](https://mill-build.org/). Requires JVM 25 and Docker (the E2E
tests spin up Dapr infrastructure via testcontainers).

```bash
./mill __.assembly      # build all assembly JARs
./mill e2e.testForked   # run the end-to-end suite
./mill slides           # render SLIDES.md to a PDF
```

### Demoing the observability example (14)

Example 14 runs as a normal E2E (rigorous assertions that traces, metrics, and
logs all reach SigNoz). To present the dashboards live without the test ever
tearing down, flip one flag — `PresentationMode` in
`e2e/src/e2e/Example14ObservabilityTest.scala` — to `true` and run only that suite:

```bash
./mill __.assembly
./mill e2e.testForked e2e.Example14ObservabilityTest
```

It then pins fixed URLs and keeps a gentle trickle of orders flowing forever
(Ctrl-C to stop):

- **SigNoz** (traces + metrics + logs): <http://localhost:3301>
- **Diagrid dashboard** (workflow state): <http://localhost:8080>

The stack is heavy (a full self-hosted SigNoz + ClickHouse); give Docker **≥4 GB**.

## Sponsors

This work has been sponsored by [Chili Piper](https://github.com/Chili-Piper).

## License

Apache-2.0
