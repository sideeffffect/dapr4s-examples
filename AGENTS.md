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

Three experimental Scala 3 features work together:

| Feature | How enabled | What it enforces |
|---|---|---|
| Capture Checking | `-language:experimental.captureChecking` (scalacOptions) | Values carry a set of *captures* (`^`). A `StateCapability^{cap}` cannot be stored anywhere that outlives `cap`. |
| Pure Functions | `-language:experimental.pureFunctions` (scalacOptions) | `A => B` is pure by default. Closures that capture capabilities get a richer type showing what they capture. |
| Safe mode | `import language.experimental.safe` (per-file) | The strictest level: enables both of the above and additionally forbids calling impure code outside `@assumeSafe` boundaries. Used in pure business-logic files. |

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

In these examples, `@assumeSafe` appears in exactly two places:
1. `given upickle.default.ReadWriter[T] = upickle.default.macroRW` — upickle is not
   capture-aware, but reading/writing JSON is a pure operation.
2. Never anywhere else — the dapr4s library already handles all other SDK boundaries.

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
         -- mill hello-state.run
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
         -- mill secrets-config.run
```

### 03 — hello-pubsub

Two processes: a subscriber (HTTP server) and a publisher (short-lived client).
Start the subscriber first, then the publisher in a second terminal.

```
# Terminal 1 — subscriber
dapr run --app-id pubsub-subscriber \
         --app-port 8083 \
         --components-path ./components \
         -- mill hello-pubsub.runMain hellopubsub.subscriber

# Terminal 2 — publisher
dapr run --app-id pubsub-publisher \
         --components-path ./components \
         -- mill hello-pubsub.runMain hellopubsub.publisher
```

### 04 — service-invocation

Two processes: a callee (HTTP server with InvocationRoutes) and a caller (client).

```
# Terminal 1 — callee
dapr run --app-id greeting-service \
         --app-port 8084 \
         --components-path ./components \
         -- mill service-invocation.runMain serviceinvocation.callee

# Terminal 2 — caller
dapr run --app-id greeting-client \
         --components-path ./components \
         -- mill service-invocation.runMain serviceinvocation.caller
```

### 05 — distributed-lock

No server. Spawns concurrent threads; shows two approaches to mutual exclusion:
`DistributedLockCapability.tryLock` and `StateCapability.saveWithETag`.

```
dapr run --app-id distributed-lock \
         --components-path ./components \
         -- mill distributed-lock.run
```

### 06 — actors

Two processes: actor server (registers the actor type) and a driver.

```
# Terminal 1 — actor server
dapr run --app-id counter-actor \
         --app-port 8086 \
         --components-path ./components \
         -- mill actors.runMain actors.actorApp

# Terminal 2 — driver
dapr run --app-id actor-driver \
         --components-path ./components \
         -- mill actors.runMain actors.actorDriver
```

### 07 — workflows

Two processes: workflow server and a driver that submits and polls.

```
# Terminal 1 — workflow server
dapr run --app-id order-workflow \
         --app-port 8087 \
         --components-path ./components \
         -- mill workflows.runMain workflows.workflowApp

# Terminal 2 — driver
dapr run --app-id workflow-driver \
         --components-path ./components \
         -- mill workflows.runMain workflows.workflowDriver
```

---

## Example progression

| # | Directory | Dapr feature | Safe Scala highlight |
|---|---|---|---|
| 1 | `hello-state/` | State CRUD, ETag, transactions | `StateCapability` cannot outlive `DaprCapability.state { }` |
| 2 | `secrets-config/` | Secrets + live config subscription | Multiple capabilities simultaneously; subscribe callback captures |
| 3 | `hello-pubsub/` | Pub/sub publish + subscribe | Handler's `PubSubCapability` context-threaded via `?=>` |
| 4 | `service-invocation/` | HTTP service-to-service calls | `InvokerCapability` and typed `InvocationRoute` handlers |
| 5 | `distributed-lock/` | Distributed lock + ETag concurrency | Lock capability ensures try/unlock pairing |
| 6 | `actors/` | Virtual actors, timers, reminders | `ActorContext` is a per-invocation capability; actor state is isolated |
| 7 | `workflows/` | Durable workflows, saga, compensation | `WorkflowContext` enables deterministic replay; activity results are `Task[O]` |

---

## Build

```
mill __.compile              # compile everything
mill hello-state.run         # run a single-main module
mill actors.runMain actors.actorApp   # run a named main in a multi-main module
```

## Code conventions

- `@scala.caps.assumeSafe` is on upickle `given` derivations only.
- Files containing pure business logic (handlers, domain rules) carry
  `import language.experimental.safe` at the top.
- `@main def` entry points do **not** carry the safe import — they are the
  impure shell (println, Thread.sleep, environment reads).
- All capability access goes through `DaprCapability.state(store) { ... }`
  companion-object scoping blocks, never through raw `summon[StateCapability]`
  stored in a `val`.
