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
./mill _.fastLinkJS
./mill e2e.testForked '*Js*'
```

### WASI-component examples (18 / 19) — prototype

dapr4s is growing a third platform: a dapr4s app built as a **WASI component** for
[dapr-wasm-components](https://github.com/sideeffffect/dapr-wasm-components) 0.7.5, with the
[scala-wasm](https://github.com/scala-wasm/scala-wasm) fork of Scala.js (`ModuleKind.WasmComponent`).
Examples 18 and 19 compile the **unchanged pure modules of 01 and 04** into components, compose
them with the dapr-wasm-components wasi-http providers and run them in `wasmtime serve` next to a
real daprd + Redis. In 19, example 04's JVM `caller` (dapr4s on the JVM) calls the Wasm
greeting-service through daprd, exactly as it calls the JVM one.

```bash
wasm/scripts/publish-dapr4s-wasm.sh all   # once: publishLocal the pinned dapr4s plugin + wasm artifacts
./mill wasm.e2e                           # build both components + 04's JVM caller, run the e2e
```

The components are ordinary modules of this Mill build (`object wasm` in `build.mill`):
`wasm.18-hello-state-shell` and `wasm.19-service-invocation-shell`, on top of `wasm.01-hello-state` and
`wasm.04-service-invocation` (01's and 04's pure sources, recompiled for the wasm platform).
`./mill show wasm.18-hello-state-shell.dapr4sComponent` builds one component and prints its path.
`./mill wasm.e2e` builds both and runs `wasm/scripts/e2e.sh`: compose (dapr-wasm-components `compose.sh` at
the `v0.7.5` tag), `dapr run … -- wasmtime serve`, and assert the real responses (the same assertions as
the JVM e2e of 01 and 04). Logs: `out/wasm/e2e.dest/logs/`.

**What a component needs.** dapr4s's Mill plugin `mill-dapr4s-wasm`, one dependency and one fixed-name
object — nothing else in the build:

```scala
//| mvnDeps: ["com.github.sideeffffect::mill-dapr4s-wasm::<version>"]   // build.mill header
import dapr4s.millwasm.*

// + wit/world.wit, wkg-config.toml, wkg.lock (here: shared, in wasm/)
object app extends Dapr4sWasmComponentModule:
  def scalaVersion = "<the Scala 3 nightly dapr4s is built with>"
  def mvnDeps = Seq(mvn"com.github.sideeffffect::dapr4s-wasm::$dapr4sWasmVersion")
// src: object dapr4s.wasm.ComponentRoot { def component: DaprComponent = MyComponent }
```

The plugin carries every build-level workaround: the scala-wasm fork of Scala.js (only in the modules that
mix it in, so examples 15–17 keep mainline Scala.js), the patched linker, the stdlib overlay, the dependency
exclusions, the WIT trampolines, `wkg wit fetch` (WIT only through OCI) and the component task
`dapr4sComponent`. The dapr4s wasm artifacts: `dapr4s-wasm` (the adapters; depends on dapr4s core, here the
published `dapr4s_sjs1_3` 3.0.0, whose Scala.js IR is linked into the component), `dapr4s-wasm-glue`
(+ `-exports`; the Scala 2.13 WIT glue), `dapr4s-wasm-scalalib-overlay` and `dapr4s-wasm-linker`.

**Prototype status.** The plugin and the artifacts are in
[dapr4s PR #47](https://github.com/sideeffffect/dapr4s/pull/47) (not released yet), hence the
`publishLocal` of the dapr4s commit pinned in `wasm/dapr4s-wasm.env` (at the version `build.mill`'s header
pins). Because `build.mill`'s header loads the plugin, **every** `./mill` command needs it:
`wasm/scripts/publish-dapr4s-wasm.sh` (default `plugin`: only the plugin, needs just git and Java) once
after cloning; `all` adds the wasm artifacts for `./mill wasm.e2e`. After a release, the header pins a
Maven Central version and the script goes away. Only state and inbound service invocation are available on
the Wasm wall so far. Upstream gaps, each worked around in dapr4s and being fixed upstream: no WIT codegen in
mainline Scala 3 (a small Scala 2.13 glue); the linker cannot pass WIT params through linear memory
(> 16 flat params: `dapr4s-wasm-linker`, scala-wasm's linker with a patch,
[scala-wasm#268](https://github.com/scala-wasm/scala-wasm/pull/268)); the linker drops WIT aliases (the
plugin re-embeds the WIT world, [scala-wasm#269](https://github.com/scala-wasm/scala-wasm/pull/269)); the
fork's and mainline's core libraries do not evict each other (the exclusions,
[scala-wasm#271](https://github.com/scala-wasm/scala-wasm/pull/271)); JS interop in the nightly's Scala.js
stdlib (`dapr4s-wasm-scalalib-overlay`); Mill has no WIT-bindings task
([mill#7784](https://github.com/com-lihaoyi/mill/pull/7784)). (The fork's organization and
`ModuleKind.WasmComponent`, [mill#7783](https://github.com/com-lihaoyi/mill/pull/7783), are released in Mill
1.3.1 and used.)

**Prerequisites**: JDK 25, Docker, the dapr CLI 1.15.1 + daprd 1.17.7 (`dapr init --slim`),
[wasm-tools](https://github.com/bytecodealliance/wasm-tools) 1.261.0,
[wasmtime](https://wasmtime.dev) 49.0.2, [wkg](https://github.com/bytecodealliance/wasm-pkg-tools) 0.16.1,
[wac](https://github.com/bytecodealliance/wac) 0.10.0, `cargo install wit-bindgen-scala --version 0.1.0`
(only for publishing the dapr4s wasm artifacts), python3 and curl.

## Build & test

Built with [Mill](https://mill-build.org/) 1.3 (the `./mill` launcher fetches it) against
dapr4s 3.0.0 (`Dapr4sVersion` in `build.mill`). Requires JVM 25 and Docker (the E2E
tests spin up Dapr infrastructure via testcontainers).

Since dapr4s 3.0.0 every sidecar error is a subclass of the sealed `dapr4s.DaprException`
(`ComponentNotFoundException`, `PermissionDeniedException`, `AppUnreachableException`,
`InvokeHttpException`, `DaprApiException`); no SDK exception reaches example code.

```bash
./mill _.assembly      # build all assembly JARs
./mill e2e.testForked   # run the end-to-end suite
./mill slides           # render SLIDES.md to a PDF
```

### Demoing the observability example (14)

Example 14 runs as a normal E2E (rigorous assertions that traces, metrics, and
logs all reach SigNoz). To present the dashboards live without the test ever
tearing down, flip one flag — `PresentationMode` in
`e2e/src/e2e/Example14ObservabilityTest.scala` — to `true` and run only that suite:

```bash
./mill _.assembly
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
