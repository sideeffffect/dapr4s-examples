# AGENTS.md — dapr4s-examples

## What this project is

Self-contained examples showing how to use [dapr4s](https://github.com/sideeffffect/dapr4s)
— a Scala 3 wrapper around [Dapr](https://dapr.io) (Distributed Application Runtime).

**The central theme of every example is Safe Scala**: Dapr effects are modelled as
`scala.caps.Capability` values tracked by the Scala 3 compiler. Instead of relying on
runtime discipline ("don't use the state client after it's closed"), the compiler enforces
it statically via capture checking.

---

## Two API styles — and a strict split across the examples

dapr4s exposes each building block two ways, and the examples are deliberately partitioned
so each style is shown on its own:

- **Primitive reified** — you hand-write the wiring: call capability methods directly
  (`PublishCapability.Instance.publish`, `InvokeCapability.invoke`, `ActorCapability.Instance.invoke`,
  `CryptoCapability.Instance.encryptString`, `DemoJob.scheduleOnce` (`JobName` sugar over `JobsCapability`),
  `WorkflowContext.callActivity`)
  and construct routes/subscriptions/activities by hand (`InvokeRoute[…]`, `Subscription[…]`,
  `ActorDefinition`, `JobRoute`, `extends WorkflowActivity`). This is the foundation.
- **Derived** — you describe a trait/object/class of method signatures and let
  `dapr4s.derivation` generate the facade (`Publish.derive`, `Invoke.derive`,
  `Actor.derive`, `Crypto.derive`, `Jobs.derive`, `InvokeRoutes.derive`,
  `Subscriptions.derive`, `WorkflowActivities.derive`, `WorkflowActivityCalls.derive`, …).
  Same types, same capture-checking, less boilerplate.

> **Rule (enforced — keep it this way):**
> - **Examples 1–11 must use ONLY the primitive reified approach.** No `import dapr4s.derivation.*`,
>   no `.derive` factories, no `@dapr4s.derivation.name`. They teach the raw building blocks.
> - **Examples 12–14 must use ONLY the derived approach** for their wiring (publishers,
>   subscriptions, routes, clients, workflow activities). They are the real-world case studies
>   that showcase derivation.
> - **Examples 15–17 are the Scala.js twins of 12–14.** They share each service's pure
>   `App.scala` verbatim (the dapr4s API is identical across platforms), so they are also
>   derived; only the shell differs — a `js.async { Dapr(config).serve … }` entry and JS
>   env-driven config (see "Scala.js examples" below). Keep them in lock-step with 12–14.
>
> Note: opening a capability *scope* (`Dapr(config).run`, `DaprCapability.state(store) { … }`,
> `DaprCapability.publish(component) { … }`) and in-handler operations that have **no** derived
> form (e.g. `StateCapability.Instance.get`/`save` over *dynamic* keys) are foundational and used by
> **both** styles — they are not "the primitive approach" being toggled here.

---

## What "Safe Scala" means here

The pure modules are compiled with `-language:experimental.safe`, which enables three things
at once:

| What | Effect |
|---|---|
| Capture checking | Values carry a set of *captures* (`^`). A `StateCapability^{cap}` cannot be stored anywhere that outlives `cap`. |
| Pure functions | `A => B` is pure by default. Closures that capture capabilities get a richer type showing what they capture. |
| Impure-call rejection | Calling functions tagged `@rejectSafe` (e.g. `println`, `Thread.sleep`) is a compile error inside safe code. |

Shell modules are compiled with `-experimental` only — enough to call `@experimental` dapr4s
APIs, but without any safe-mode restrictions.

### What this looks like in practice

```scala
Dapr(config).run:                                  // DaprCapability in scope
  DaprCapability.state(StateStoreName("statestore")): // StateCapability.Instance^{cap} in scope
    StateCapability.Instance.save(key, value)      // OK — inside the scope

// HERE: StateCapability.Instance is gone. Using it here is a compile error.
```

The compiler rejects any attempt to assign, return, or otherwise smuggle a capability out
of its governing block. This is a stronger guarantee than any lock or close() call.

### The `@scala.caps.assumeSafe` escape hatch

Code that crosses the Java boundary (upickle macro derivations, Dapr SDK internals) cannot
be checked by the Scala 3 capture checker. The `@scala.caps.assumeSafe` annotation says
*"I assert this is safe at this boundary — trust me."*

In these examples, `@assumeSafe` guards only the upickle JSON-codec derivations in the
shell modules — upickle is not capture-aware, but reading/writing JSON is a pure
operation. Simple examples put it on the single `given` derivation; the multi-service
case studies (08, 09) put it on an `@scala.caps.assumeSafe object Codecs` that holds all
the `JsonCodec[T]` givens for that service.

---

## Module structure

Each example is split into two Mill modules:

| Module | Compiled with | Purpose |
|---|---|---|
| `<name>` | `-language:experimental.safe` | Capture-checked "safe" code. Domain model, capability-scoped business logic, activities, workflows, even `ServerApp` / handlers — anything that can satisfy capture checking. Capabilities are received per call and used within that call, never stored in a field. |
| `<name>-shell` | `-experimental` | The trusted core that safe mode genuinely rejects: the `@scala.caps.assumeSafe` upickle JSON-codec derivations and the `@main` entry points (which do console I/O and construct/run `Dapr(config)`). |

The split is *not* "pure = no I/O, shell = entry point". A pure module routinely hosts
the server app and its request/workflow handlers: because each capability (e.g.
`DaprCapability`, `InvokeCapability`) is passed into a call and used only
within it — never captured in a field — capture checking is satisfied without
`@assumeSafe`. Example 13 (`13-order-service`) demonstrates this: its saga workflow, all
five activities, and `ServerApp` live in the *pure* module; only `object Codecs`
(`@assumeSafe`) and the `@main` entries live in `13-order-service-shell`. The library
entry point is `class Dapr` (`Dapr(config).run { ... }` / `Dapr(config).serve { ... }`),
which is the one impure thing the `@main`s do.

Source files live under `src/<package>/` within each module directory, e.g.:
```
01-hello-state/src/hellostate/App.scala
01-hello-state-shell/src/hellostate/Main.scala
```

---

## Prerequisites

- **Dapr CLI** and runtime: https://docs.dapr.io/getting-started/install-dapr-cli/
- **Redis** on `localhost:6379` — `docker run -d -p 6379:6379 redis:7`
- **JVM ≥ 21** — `java -version`
- **Mill 1.3.x** — use the `./mill` launcher (it reads `.mill-version`). Mill >= 1.3.0 is required
  for the Scala.js JSPI switch (see "Scala.js examples").
- **dapr4s published locally** (run once from the dapr4s repo):
  ```
  cd ../scala-safe-dapr
  scala-cli --power publish local .
  ```

---

## Formatting

The project uses scalafmt (config in `.scalafmt.conf`). Format everything with
`./mill mill.scalalib.scalafmt/reformatAll`; check without writing (as CI does) with
`./mill mill.scalalib.scalafmt/checkFormatAll`. The CI `format` job blocks the build on any
unformatted source.

Files using experimental capture-checking `^{...}` return-type syntax scalafmt can't parse are
listed in `project.excludeFilters` in `.scalafmt.conf` — add new such files there.

---

## Running examples

All component YAMLs live in `components/`. Every `dapr run` picks them up via
`--components-path ./components`.

### 01 — hello-state

No server needed. Demonstrates state CRUD, ETag-guarded saves, and transactions.

```
dapr run --app-id hello-state \
         --components-path ./components \
         -- mill 01-hello-state-shell.run
```

### 02 — secrets-config

No server needed. Reads secrets and configuration; subscribes to live config changes.

Set up a secret first:
```
export MY_API_KEY="s3cr3t-value"
```

```
dapr run --app-id secrets-config \
         --components-path ./components \
         -- mill 02-secrets-config-shell.run
```

### 03 — hello-pubsub

Two processes: a subscriber (HTTP server) and a publisher (short-lived client).
Start the subscriber first, then the publisher in a second terminal.

```
# Terminal 1 — subscriber
dapr run --app-id pubsub-subscriber \
         --app-port 8083 \
         --components-path ./components \
         -- mill 03-hello-pubsub-shell.runMain hellopubsub.subscriber

# Terminal 2 — publisher
dapr run --app-id pubsub-publisher \
         --components-path ./components \
         -- mill 03-hello-pubsub-shell.runMain hellopubsub.publisher
```

### 04 — service-invocation

Two processes: a callee (HTTP server with InvokeRoutes) and a caller (client). The caller
also calls a route the callee does not serve: `invokeHttp` returns the 404 as a value, the typed
`invoke` throws `InvokeHttpException`.

```
# Terminal 1 — callee
dapr run --app-id greeting-service \
         --app-port 8084 \
         --components-path ./components \
         -- mill 04-service-invocation-shell.runMain serviceinvocation.callee

# Terminal 2 — caller
dapr run --app-id greeting-client \
         --components-path ./components \
         -- mill 04-service-invocation-shell.runMain serviceinvocation.caller
```

### 05 — distributed-lock

No server. Simulates sequential workers under a distributed lock and demonstrates
double-acquire behaviour and an unlock by a non-owner (`UnlockStatus.LockBelongsToOthers`).

```
dapr run --app-id distributed-lock \
         --components-path ./components \
         -- mill 05-distributed-lock-shell.run
```

### 06 — actors

Two processes: actor server (registers the actor type) and a driver.

```
# Terminal 1 — actor server
dapr run --app-id counter-actor \
         --app-port 8086 \
         --components-path ./components \
         -- mill 06-actors-shell.runMain actors.actorApp

# Terminal 2 — driver
dapr run --app-id actor-driver \
         --components-path ./components \
         -- mill 06-actors-shell.runMain actors.actorDriver
```

### 07 — workflows

Two processes: workflow server and a driver that submits and polls.

```
# Terminal 1 — workflow server
dapr run --app-id order-workflow \
         --app-port 8087 \
         --components-path ./components \
         -- mill 07-workflows-shell.runMain workflows.workflowServer

# Terminal 2 — driver
dapr run --app-id workflow-driver \
         --components-path ./components \
         -- mill 07-workflows-shell.runMain workflows.workflowDriver
```

### 08 — scan-pipeline

Real-world case study: a Grafana-style, event-driven vulnerability scanner built as a
fan-out pub/sub pipeline across three services (each with its own `-shell`):

- **scan-gateway** — accepts submissions and publishes to the `scanRequested` topic
  (`scanGateway` server; `scanSeed` is a one-shot publisher).
- **scan-worker** — subscribes to `scanRequested`, runs the (stubbed) scan, and publishes
  to `scanCompleted`. A request that keeps failing past the sidecar's retry policy is
  routed to the real dead-letter topic (`scanDeadLetter`).
- **scan-results** — subscribes to `scanCompleted` (folding results into a running
  dashboard) and to the dead-letter topic `scanDeadLetter` (counting failed requests).

```
# Terminal 1 — results dashboard
dapr run --app-id scan-results \
         --app-port 8090 \
         --components-path ./components \
         -- mill 08-scan-results-shell.runMain scanresults.scanResults

# Terminal 2 — worker
dapr run --app-id scan-worker \
         --app-port 8089 \
         --components-path ./components \
         -- mill 08-scan-worker-shell.runMain scanworker.scanWorker

# Terminal 3 — gateway (HTTP server)
dapr run --app-id scan-gateway \
         --app-port 8088 \
         --components-path ./components \
         -- mill 08-scan-gateway-shell.runMain scangateway.scanGateway

# Terminal 4 — seed a batch of scan requests
dapr run --app-id scan-gateway \
         --components-path ./components \
         -- mill 08-scan-gateway-shell.runMain scangateway.scanSeed
```

### 09 — order-fulfillment

Real-world case study: a ZEISS-style order-fulfillment **saga** orchestrated by a durable
workflow across four services (each with its own `-shell`):

- **order-service** — runs `OrderProcessingWorkflow`, which calls the downstream services
  in sequence (reserve → charge → dispatch) and compensates on failure (release / refund).
  Hosts a `submit-order` invocation route. `orderServer` is the workflow/server app;
  `orderDriver` submits the sample orders and prints outcomes.
- **inventory-service** — `reserve` / `release` (`inventoryServer`).
- **payment-service** — `charge` / `refund` (`paymentServer`).
- **shipping-service** — `dispatch` (`shippingServer`).

```
# Terminals 1–3 — downstream services
dapr run --app-id inventory-service --app-port 8092 \
         --components-path ./components \
         -- mill 09-inventory-service-shell.runMain inventoryservice.inventoryServer

dapr run --app-id payment-service --app-port 8093 \
         --components-path ./components \
         -- mill 09-payment-service-shell.runMain paymentservice.paymentServer

dapr run --app-id shipping-service --app-port 8094 \
         --components-path ./components \
         -- mill 09-shipping-service-shell.runMain shippingservice.shippingServer

# Terminal 4 — order-service (workflow + submit-order route)
dapr run --app-id order-service --app-port 8091 \
         --components-path ./components \
         -- mill 09-order-service-shell.runMain orderservice.orderServer

# Terminal 5 — driver (submits the sample orders)
dapr run --app-id order-service \
         --components-path ./components \
         -- mill 09-order-service-shell.runMain orderservice.orderDriver
```

### 14 — observability + Diagrid dashboard

Unlike the others, this example is driven through its E2E (it needs the whole
SigNoz + Diagrid stack, wired by `e2e/docker/docker-compose.14-observability.yml`):

```bash
# normal: bounded run, rigorous assertions (traces+metrics+logs reach SigNoz)
./mill e2e.testForked '*observability*'
```

For a live demo, set `PresentationMode = true` in
`e2e/src/e2e/Example14ObservabilityTest.scala` and run the same command — it pins
fixed URLs (SigNoz <http://localhost:3301>, Diagrid <http://localhost:8080>) and
trickles orders forever (Ctrl-C to stop). See `SPEC-14-observability.md`.

---

## Example progression

Each module has a matching `-shell/` alongside it. **Style** is the API style each example
is locked to (see "Two API styles" above): examples 1–11 are **reified**, 12–14 are **derived**.

| # | Pure module(s) | Dapr feature | Safe Scala highlight | Style |
|---|---|---|---|---|
| 1 | `01-hello-state/` | State CRUD, ETag, transactions | `StateCapability` cannot outlive `DaprCapability.state { }` | reified |
| 2 | `02-secrets-config/` | Secrets + live config subscription | Multiple capabilities simultaneously; subscribe callback captures | reified |
| 3 | `03-hello-pubsub/` | Pub/sub publish + subscribe | `PublishCapability.Instance.publish` direct; handler captures the capability | reified |
| 4 | `04-service-invocation/` | HTTP service-to-service calls | `InvokeCapability.invoke` + typed `InvokeRoute` handlers; `invokeHttp` (non-2xx as a value) vs `InvokeHttpException` | reified |
| 5 | `05-distributed-lock/` | Distributed lock | Lock capability ensures try/unlock pairing; exhaustive `UnlockStatus` match (`LockBelongsToOthers`) | reified |
| 6 | `06-actors/` | Virtual actors, timers, reminders | `ActorContext` per-invocation capability; `ActorCapability.Instance.invoke` client | reified |
| 7 | `07-workflows/` | Durable workflows, saga, compensation | `WorkflowContext` deterministic replay; `callActivity` returns `Task[O]` | reified |
| 8 | `08-cryptography/` | Encrypt/decrypt via `crypto.dapr.localstorage` | `CryptoCapability.Instance.encryptString`/`encrypt` over immutable `ArraySeq[Byte]` | reified |
| 9 | `09-jobs/` | Schedule a job; sidecar fires it back to a `JobRoute` | `DemoJob.scheduleOnce` (`JobName` sugar over `JobsCapability`, client) + `JobRoute` (trigger) | reified |
| 10 | `10-conversation/` | LLM conversation (alpha1 + alpha2) | `ConversationCapability` against the `conversation.echo` component | reified |
| 11 | `11-bindings/` | Output + input on one bidirectional connector | `BindingsCapability` invoke / invokeOneWay (output) + `BindingRoute` (input) | reified |
| 12 | `12-scan-gateway/`, `12-scan-worker/`, `12-scan-results/` | Grafana-style fan-out pub/sub pipeline with a real dead-letter queue | Three services; derived publishers (`Publish.derive`), subscriptions, and routes; dead-letter topic counted in the dashboard | derived |
| 13 | `13-order-service/`, `13-inventory-service/`, `13-payment-service/`, `13-shipping-service/` | ZEISS-style order-fulfillment saga (durable workflow + service invocation + compensation) | Derived clients (`Invoke.derive`) + activities (`WorkflowActivities`/`WorkflowActivityCalls.derive`); saga, activities, and `ServerApp` stay pure | derived |
| 14 | `14-orders/`, `14-pricing/` | Observability: `OrderWorkflow` → service invocation (pricing) + pub/sub; daprd OTLP tracing into SigNoz; workflow state in the Diagrid dashboard | Derived activities + subscriptions; I/O activities receive `DaprCapability` per call; the audit handler stays pure — `println` is `@rejectSafe` | derived |
| 15 | `15-scan-gateway/`, `15-scan-worker/`, `15-scan-results/` | **Scala.js** twin of 12 (same pipeline on Node + JSPI) | Shares 12's pure `App.scala`; JS shell + Wasm `fastLinkJS`; `Example15ScanPipelineJsTest` | derived (JS) |
| 16 | `16-order-service/`, `16-inventory-service/`, `16-payment-service/`, `16-shipping-service/` | **Scala.js** twin of 13 (saga on Node + JSPI) | Shares 13's pure `App.scala`; cross-service invocation by app-id works the same; `Example16OrderFulfillmentJsTest` | derived (JS) |
| 17 | `17-orders/`, `17-pricing/` | **Scala.js** twin of 14's workload (`OrderWorkflow` → invocation + pub/sub; no SigNoz) | Shares 14's pure `App.scala`; `Example17TelemetryJsTest` drives the workflow via the Dapr HTTP API | derived (JS) |

### Scala.js examples (15 / 16 / 17)

The `15-/16-/17-*` modules are `JsPureModule`/`JsShellModule` (Mill `ScalaJSModule` with
`scalaJSExperimentalUseWebAssembly` + `scalaJSUseWebAssemblyJSPI`, ES modules, ES2022), depending on
`dapr4s_sjs1_3`. Scala.js 1.22 refuses to link `js.async`/`js.await` on Wasm without the explicit
JSPI switch ("Uses an async block without JSPI support in WebAssembly"), and Mill exposes it only
from 1.3.0 — do not downgrade Mill below that. The 1.22 Wasm backend also requires ES2022. Each
service's pure `App.scala` is **copied verbatim from its JVM twin** (12/13/14) — keep them in
sync; the only hand-written JS file is the shell `Main.scala` (upickle codecs + a single
`js.async { Dapr(config).serve … }` entry reading ports from `process.env`). Build a service
with `./mill <NN-svc>-shell.fastLinkJS` (output: `out/<…>/fastLinkJS.dest/main.js` + `.wasm`).
The E2E (`MultiNodeInfra` + `docker-compose.{15,16,17}-*.yml`) runs each as a Node 25 container
(`node main.js`, JSPI on) + daprd sidecar; the three suites carry `munitIgnore = env contains
E2E_SKIP_JS` so the `core` CI leg skips them and the dedicated `e2e-scala-js` job runs them
(Node 25 + `npm ci` in `e2e/js` + `fastLinkJS` + `e2e.testForked '*Js*'`). Runtime npm deps
(`@dapr/dapr`, `express`) live in `e2e/js/`.

### WASI-component examples (18 / 19) — prototype

`wasm/` holds a **separate sbt build** that turns two existing pure modules into WASI
**components** for [dapr-wasm-components](https://github.com/sideeffffect/dapr-wasm-components)
0.7.0 (scala-wasm fork of Scala.js, `ModuleKind.WasmComponent`), run in `wasmtime serve` next to a
real daprd:

| Component | Pure module (unchanged, shared with the JVM) | Wasm shell |
|---|---|---|
| `18-hello-state-shell` | `01-hello-state` (`HelloStateApp.run`) | route `run` returns 01's report lines |
| `19-service-invocation-shell` | `04-service-invocation` (`CalleeApp.app`) | the greeting-service; 04's JVM `caller` calls it |

- **Why sbt, why separate**: the scala-wasm fork ships only an sbt plugin, and it is a fork of
  sbt-scalajs with the same classes, so it cannot share a build with 15–17's mainline Scala.js. dapr4s
  keeps its own wasm build apart for the same reason. **Mill stays the entry point**: `object wasm`
  in `build.mill` shells out to `wasm/scripts/*.sh` (`./mill wasm.publishDapr4s`, `wasm.build`,
  `wasm.e2e`). Hacky by design: Mill caches nothing there; sbt keeps its own state in `wasm/target`.
- **Same pure code**: the sbt build compiles `../01-hello-state/src` and `../04-service-invocation/src`
  with the same nightly (read from `build.mill`'s `ScalaVersion`) and the same flags as Mill's
  `PureModule`, against the shared dapr4s API only. The pure modules expose a `StateCapability.Instance`-only
  entry (`HelloStateApp.run`, `CalleeApp.app`) because the Wasm wall hands a component only state;
  the JVM `apply()`s just open that instance and delegate. Keep it that way when editing 01/04.
- **Classpath = Maven Central dapr4s + a small local wasm layer**: `dapr4s_sjs1_3:3.0.0` (Central,
  Scala.js IR, linked into the component by the scala-wasm linker) + the dapr4s wasm wall, its 2.13
  WIT bindings/glue and the `sbt-dapr4s-wasm` plugin, which are **not on Central yet**: they come
  from a `publishLocal` of the dapr4s commit pinned in `wasm/dapr4s-wasm.env` (a commit of
  [dapr4s PR #44](https://github.com/sideeffffect/dapr4s/pull/44), built in its "release mode" so the
  layer depends on Central `dapr4s_sjs1_3`). `wasm/scripts/publish-dapr4s-wasm.sh` does the clone,
  the patched-linker build and the publish, and fails if the published version differs from the pin.
  **When PR #44 is merged and the layer is released, move the pin to the release version, delete
  the publish script and its CI steps, and resolve everything from Central.** To bump the pin: set
  `DAPR4S_COMMIT` + `DAPR4S_WASM_VERSION` (the sbt-dynver version of that commit,
  `<tag>+<n>-<sha8>-SNAPSHOT`) together.
- **The component contract** (what a shell writes): `object X extends dapr4s.wasm.DaprComponent`
  (`def app(using StateCapability): DaprApp`) + the fixed-name `object dapr4s.wasm.ComponentRoot { def
  component = X }` (a reactor has no `main`; a missing one is a link error). Codecs: upickle 3.3.1 like
  the JVM shells. Only state + inbound service invocation exist on the Wasm wall today.
- **WIT only through OCI**: `wasm/wit/world.wit` = `include dapr-wasm-components:interfaces/app@0.7.0;`,
  `wasm/wkg-config.toml` (upstream), `wasm/wkg.lock` committed, `wasm/wit/deps/` git-ignored. The
  plugin runs `wkg wit fetch` and re-embeds that world into each component.
- **Composition**: `wasm/scripts/compose.sh` runs the upstream `compose.sh` pinned at the **v0.7.0 tag**
  (never `main`). At that tag it exits 1 even on success (its EXIT trap), so its exit code is ignored
  and success is judged by a validating output file.
- **E2E** (`wasm/scripts/e2e.sh`, `./mill wasm.e2e`, CI job `e2e-wasm`): Redis in Docker
  (`localhost:26490`), daprd via `dapr run` (slim), `wasmtime serve`; asserts 18's report lines (the
  same as `Example01HelloStateTest`), 19's greet/stats/404 (the same cases as
  `Example04ServiceInvocationTest`) and the output of 04's JVM caller against the Wasm callee.
- **Formatting**: the sbt build checks its own sources (`scalafmtCheckAll scalafmtSbtCheck`, run by
  `wasm/scripts/build.sh`) with the root `.scalafmt.conf`; Mill's `checkFormatAll` does not see them.
- **Tools** (CI pins the same): JDK 25, sbt, Docker, dapr CLI 1.15.1 + daprd 1.17.7
  (`dapr init --slim`), wasm-tools 1.261.0, wasmtime 49.0.2, wkg 0.16.1, wac 0.10.0,
  wit-bindgen-scala 0.1.0 (`cargo install`), python3, curl.
- **Upstream scala-wasm blockers** (worked around in dapr4s, see its AGENTS.md "Wasm component layer
  rules"): no WIT codegen in mainline Scala 3 (2.13 glue), linker cannot read export params from
  linear memory (patched linker built from source), linker drops WIT aliases (WIT re-embed), nightly
  stdlib uses JS interop (`.sjsir` overlay), no `main` in a reactor (`ComponentRoot`).

> The E2E (`Example14ObservabilityTest`) proves all three OpenTelemetry signals reach
> SigNoz by asserting the funnel collector's own pipeline counters
> (`otelcol_exporter_sent_spans` / `_metric_points` / `_log_records` > 0) — no SigNoz
> API auth needed. Flip `PresentationMode = true` in that test to hold the whole stack
> up forever with fixed URLs (SigNoz `:3301`, Diagrid `:8080`) for a live demo. See
> `SPEC-14-observability.md`.

---

## Build

```
mill __.compile                                           # compile everything
mill 01-hello-state-shell.run                           # run a single-main shell
mill 06-actors-shell.runMain actors.actorApp            # run a named main in a multi-main shell
```

## Testing (e2e)

The `e2e` Mill module (`object e2e extends BaseModule with TestModule.Munit` in
`build.mill`) holds ~9 munit suites that exercise the examples end-to-end against real
Dapr infrastructure spun up via Docker Compose + Testcontainers (no `dapr` CLI needed on
the host). Each suite — `Example01HelloStateTest`, `Example02SecretsConfigTest`,
`Example03HelloPubSubTest`, `Example04ServiceInvocationTest`, `Example05DistributedLockTest`,
`Example06ActorsTest`, `Example07WorkflowsTest`, `Example08ScanPipelineTest`,
`Example09OrderFulfillmentTest`, `Example10CryptographyTest`, `Example11JobsTest`,
`Example12ConversationTest` — receives the relevant shell's assembly JAR
via `-De2e.jar.<name>=…` system properties (see `forkArgs`).

Run the full suite (assemblies first, then the forked tests):
```
./mill __.assembly        # build every shell's assembly JAR
./mill e2e.testForked     # run the e2e suites (Docker required)
```

This is exactly what CI runs (`.github/workflows/e2e.yml`, job `e2e`). The WASI-component
examples have their own entry point and CI job: `./mill wasm.e2e` / `e2e-wasm` (see
"WASI-component examples (18 / 19)").

## Slides

`SLIDES.md` is the presentation deck. The `slides` Mill target renders it to PDF: it
pre-renders each ```` ```mermaid ```` block to a PNG with the Mermaid CLI (`mmdc`) and
then runs Marp (`npx @marp-team/marp-cli`) to produce the PDF at
`out/slides.dest/SLIDES.pdf`.

```
./mill slides             # build the PDF -> out/slides.dest/SLIDES.pdf
./mill show slides        # same, also prints the PDF path as JSON
```

Requires `mmdc`, `npx`, and a Chromium/Chrome on PATH (override with `CHROME_PATH`). CI
also renders the deck (`.github/workflows/e2e.yml`, job `slides`).

## Code conventions

- `@scala.caps.assumeSafe` appears only on upickle `given` derivations in shell modules.
- Pure modules (`PureModule` in `build.mill`) are compiled with `-language:experimental.safe`;
  no per-file feature import is needed or present.
- Shell modules (`Dapr4sModule`) are compiled with `-experimental` only — impure code is fine.
- `DaprCapability.state(store) { ... }` and similar companion-object scoping blocks open
  capability scopes inside pure functions; raw `summon[StateCapability.Instance]` stored in a `val`
  is never used.
- Capability tiers (dapr4s capability hierarchy): `DaprCapability.x` is the universal
  `XCapability` (methods take the store/component name); `.instance(name)` narrows it to
  `XCapability.Instance`, which is what the name-taking transformers (`DaprCapability.state(store)`,
  `publish`, `secrets`, `configuration`, `lock`, `crypto`, `bindings`, `actor`) put in scope —
  so business code takes `using StateCapability.Instance` and calls `StateCapability.Instance.get(…)`.
  Read-only code can ask for the narrower `StateCapability.Instance.ReadOnly` (State, Secrets,
  Configuration, Jobs, Workflow have ReadOnly tiers). `DaprCapability.invoke`/`workflow`/`jobs`
  give the universal capability. Each family lives in its own package — import it alongside the
  root: `import dapr4s.*` + `import dapr4s.state.*` (`.invoke`, `.publish`, `.actor`, `.workflow`, …).
- Handle sugar: `WorkflowInstanceId` and (JVM) `JobName` carry tier-aware extension methods —
  `id.getStatus` / `id.waitForCompletion(t)` need only `WorkflowCapability.ReadOnly`,
  `id.suspend()`/`resume()`/`terminate()`/`purge()`/`raiseEvent(…)` need `WorkflowCapability`;
  `name.get` needs `JobsCapability.ReadOnly`, `name.schedule(…)`/`scheduleOnce(…)`/`delete()` need
  `JobsCapability`. Prefer them where they read better (examples 07, 09, 13/16).
- `JsonCodec[T]` instances are derived in shells (macro expansion is impure) and passed into
  pure functions as `using` parameters.
