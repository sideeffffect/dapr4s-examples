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

## Build & test

Built with [Mill](https://mill-build.org/). Requires JVM 25 and Docker (the E2E
tests spin up Dapr infrastructure via testcontainers).

```bash
./mill __.assembly      # build all assembly JARs
./mill e2e.testForked   # run the end-to-end suite
./mill slides           # render SLIDES.md to a PDF
```

## Sponsors

This work has been sponsored by [Chili Piper](https://github.com/Chili-Piper).

## License

Apache-2.0
