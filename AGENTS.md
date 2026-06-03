# AGENTS.md — dapr4s-examples

## What this project is

Self-contained examples showing how to use [dapr4s](https://github.com/sideeffffect/dapr4s)
— a Scala 3 wrapper around [Dapr](https://dapr.io) (Distributed Application Runtime).

**The central theme of every example is Safe Scala**: Dapr effects are modelled as
`scala.caps.Capability` values tracked by the Scala 3 compiler. Instead of relying on
runtime discipline ("don't use the state client after it's closed"), the compiler enforces
it statically via capture checking.

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
  DaprCapability.state(StoreName("statestore")):   // StateCapability^{cap} in scope
    StateCapability.save(key, value)               // OK — inside the scope

// HERE: StateCapability is gone. Using it here is a compile error.
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
`DaprCapability`, `ServiceInvocationCapability`) is passed into a call and used only
within it — never captured in a field — capture checking is satisfied without
`@assumeSafe`. Example 09 (`09-order-service`) demonstrates this: its saga workflow, all
five activities, and `ServerApp` live in the *pure* module; only `object Codecs`
(`@assumeSafe`) and the `@main` entries live in `09-order-service-shell`. The library
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
- **Mill 0.12.x** — `mill --version`
- **dapr4s published locally** (run once from the dapr4s repo):
  ```
  cd ../scala-safe-dapr
  scala-cli --power publish local .
  ```

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

Two processes: a callee (HTTP server with InvocationRoutes) and a caller (client).

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
double-acquire behaviour.

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

- **scan-gateway** — accepts submissions and publishes to the `scan-requested` topic
  (`scanGateway` server; `scanSeed` is a one-shot publisher).
- **scan-worker** — subscribes to `scan-requested`, runs the (stubbed) scan, and publishes
  to `scan-completed`. A request that keeps failing past the sidecar's retry policy is
  routed to the real dead-letter topic (`scan-dead-letter`).
- **scan-results** — subscribes to `scan-completed` (folding results into a running
  dashboard) and to the dead-letter topic `scan-dead-letter` (counting failed requests).

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

---

## Example progression

| # | Pure module | Shell module | Dapr feature | Safe Scala highlight |
|---|---|---|---|---|
| 1 | `01-hello-state/` | `01-hello-state-shell/` | State CRUD, ETag, transactions | `StateCapability` cannot outlive `DaprCapability.state { }` |
| 2 | `02-secrets-config/` | `02-secrets-config-shell/` | Secrets + live config subscription | Multiple capabilities simultaneously; subscribe callback captures |
| 3 | `03-hello-pubsub/` | `03-hello-pubsub-shell/` | Pub/sub publish + subscribe | Handler's `PubSubCapability` context-threaded via `?=>` |
| 4 | `04-service-invocation/` | `04-service-invocation-shell/` | HTTP service-to-service calls | `ServiceInvocationCapability` and typed `InvocationRoute` handlers |
| 5 | `05-distributed-lock/` | `05-distributed-lock-shell/` | Distributed lock | Lock capability ensures try/unlock pairing |
| 6 | `06-actors/` | `06-actors-shell/` | Virtual actors, timers, reminders | `ActorContext` is a per-invocation capability; actor state is isolated |
| 7 | `07-workflows/` | `07-workflows-shell/` | Durable workflows, saga, compensation | `WorkflowContext` enables deterministic replay; activity results are `Task[O]` |
| 8 | `08-scan-gateway/`, `08-scan-worker/`, `08-scan-results/` | matching `-shell/` modules | Grafana-style fan-out pub/sub pipeline with a real dead-letter queue | Three services; subscribers' `PubSubCapability` threaded into handlers; dead-letter topic counted in the dashboard |
| 9 | `09-order-service/`, `09-inventory-service/`, `09-payment-service/`, `09-shipping-service/` | matching `-shell/` modules | ZEISS-style order-fulfillment saga (durable workflow + service invocation + compensation) | Saga workflow, all activities, and `ServerApp` are pure: `execute` receives `DaprCapability` per call, so no capability is captured in a field |

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
`Example09OrderFulfillmentTest` — receives the relevant shell's assembly JAR
via `-De2e.jar.<name>=…` system properties (see `forkArgs`).

Run the full suite (assemblies first, then the forked tests):
```
./mill __.assembly        # build every shell's assembly JAR
./mill e2e.testForked     # run the e2e suites (Docker required)
```

This is exactly what CI runs (`.github/workflows/e2e.yml`, job `e2e`).

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
  capability scopes inside pure functions; raw `summon[StateCapability]` stored in a `val`
  is never used.
- `JsonCodec[T]` instances are derived in shells (macro expansion is impure) and passed into
  pure functions as `using` parameters.
