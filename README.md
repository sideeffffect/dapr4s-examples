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
| 04 | Service invocation (typed `invoke`; `invokeHttp` returns a non-2xx status as a value, typed `invoke` throws `InvokeHttpException`) |
| 05 | Distributed lock (an unlock by a non-owner reports `UnlockStatus.LockBelongsToOthers`) |
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
| 18 | State store **as a WASI component** — 01's pure code in `wasmtime serve` next to daprd (prototype) |
| 19 | Service invocation **as a WASI component** — 04's greeting-service, called by 04's unchanged JVM caller (prototype) |

### Scala.js examples (15 / 16 / 17)

dapr4s cross-compiles to Scala.js (the `dapr4s_sjs1_3` artifact, with the Dapr JS SDK
facades embedded), so the same `App.scala` runs unchanged on Node. Examples 15–17
re-implement the real-world case studies 12–14 on Scala.js to prove the published JS
artifact end to end: each service is the **same pure module** as its JVM twin, with only
the shell's entry point differing (a single `js.async { Dapr(config).serve … }` at the
program edge — `serve` suspends the WebAssembly stack via JSPI instead of parking a
virtual thread). They link with Scala.js 1.22's Wasm backend (`scalaJSExperimentalUseWebAssembly`
plus the explicit JSPI switch `scalaJSUseWebAssemblyJSPI`, which needs Mill >= 1.3.0; ES modules,
ES2022) and run as Node 25 processes (JSPI is on by default there), each paired
with its own daprd sidecar — exactly like the JVM examples, but `node main.js` instead of
`java -cp out.jar`.

```bash
# Node 25 + the runtime npm deps the bundles import (@dapr/dapr, express):
(cd e2e/js && npm ci)
# Link the Wasm bundles, then run the three JS suites:
./mill __.fastLinkJS
./mill e2e.testForked '*Js*'
```

### WASI-component examples (18 / 19) — prototype

dapr4s is growing a third platform: a dapr4s app built as a **WASI component** for
[dapr-wasm-components](https://github.com/sideeffffect/dapr-wasm-components) 0.7.1, with the
[scala-wasm](https://github.com/scala-wasm/scala-wasm) fork of Scala.js (`ModuleKind.WasmComponent`).
Examples 18 and 19 compile the **unchanged pure modules of 01 and 04** into components, compose
them with the dapr-wasm-components wasi-http providers and run them in `wasmtime serve` next to a
real daprd + Redis. In 19, example 04's JVM `caller` (dapr4s on the JVM) calls the Wasm
greeting-service through daprd, exactly as it calls the JVM one.

```bash
./mill wasm.e2e    # publishLocal the dapr4s wasm layer, build both components, run the e2e
```

What `./mill wasm.e2e` does (each step is a script in `wasm/scripts/`, usable on its own):

1. `publish-dapr4s-wasm.sh` — clones dapr4s at the commit pinned in `wasm/dapr4s-wasm.env`, builds
   the patched scala-wasm linker and `publishLocal`s the dapr4s wasm layer + `sbt-dapr4s-wasm` plugin
   (skipped when already in `~/.ivy2/local`);
2. `build.sh` — `sbt` in `wasm/`: format check, link, and re-embed the WIT world →
   `wasm/target/components/{18-hello-state-shell,19-service-invocation-shell}.wasm`;
3. `e2e.sh` — compose (dapr-wasm-components `compose.sh` at the `v0.7.1` tag), `dapr run … --
   wasmtime serve`, and assert the real responses (the same assertions as the JVM e2e of 01 and 04).

**Why a separate sbt build?** The scala-wasm fork exists only as an sbt plugin, and it is a fork of
sbt-scalajs, so it cannot live in the same build as examples 15–17 (mainline Scala.js). Mill stays
the entry point and simply shells out to sbt (and to wkg, wasm-tools, wasmtime, the dapr CLI).

**Prototype status.** dapr4s itself comes from Maven Central (`dapr4s_sjs1_3` 3.0.0, linked into the
component). The small Wasm layer on top (wall + Scala 2.13 WIT glue + sbt plugin) is still in
[dapr4s PR #44](https://github.com/sideeffffect/dapr4s/pull/44) and not on Central, hence the
`publishLocal` of a pinned commit; after that PR is released, it becomes an ordinary dependency.
Only state and inbound service invocation are available on the Wasm wall so far. Upstream scala-wasm
blockers, each worked around in dapr4s: no WIT codegen in mainline Scala 3 (a small Scala 2.13 glue),
a linker `???` for exports with more than 16 flat params (a patched linker built from source), the
linker dropping WIT aliases (the WIT world is re-embedded with `wasm-tools`), and JS interop in the
nightly's Scala.js stdlib (a `.sjsir` overlay).

**Prerequisites**: JDK 25, sbt, Docker, the dapr CLI 1.15.1 + daprd 1.17.7 (`dapr init --slim`),
[wasm-tools](https://github.com/bytecodealliance/wasm-tools) 1.261.0,
[wasmtime](https://wasmtime.dev) 49.0.2, [wkg](https://github.com/bytecodealliance/wasm-pkg-tools) 0.16.1,
[wac](https://github.com/bytecodealliance/wac) 0.10.0, `cargo install wit-bindgen-scala --version 0.1.0`,
python3 and curl.

## Build & test

Built with [Mill](https://mill-build.org/) 1.3 (the `./mill` launcher fetches it) against
dapr4s 3.0.0 (`Dapr4sVersion` in `build.mill`). Requires JVM 25 and Docker (the E2E
tests spin up Dapr infrastructure via testcontainers).

Since dapr4s 3.0.0 every sidecar error is a subclass of the sealed `dapr4s.DaprException`
(`ComponentNotFoundException`, `PermissionDeniedException`, `AppUnreachableException`,
`InvokeHttpException`, `DaprApiException`); no SDK exception reaches example code.

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
