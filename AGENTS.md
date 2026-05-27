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
DaprRuntime.run(DaprRuntimeConfig()):              // DaprCapability in scope
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

In these examples, `@assumeSafe` appears in exactly one place per shell module:
- `given upickle.default.ReadWriter[T] = upickle.default.macroRW` — upickle is not
  capture-aware, but reading/writing JSON is a pure operation.

---

## Module structure

Each example is split into two Mill modules:

| Module | Compiled with | Purpose |
|---|---|---|
| `<name>` | `-language:experimental.safe` | Pure business logic. No I/O. Returns structured result types. |
| `<name>-shell` | `-experimental` | Impure entry point. Derives codecs, starts `DaprRuntime`, prints results. |

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

---

## Build

```
mill __.compile                                           # compile everything
mill 01-hello-state-shell.run                           # run a single-main shell
mill 06-actors-shell.runMain actors.actorApp            # run a named main in a multi-main shell
```

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
