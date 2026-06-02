---
marp: true
theme: default
paginate: true
style: |
  section {
    font-family: 'Helvetica Neue', Helvetica, Arial, sans-serif;
    font-size: 1.4rem;
  }
  section.title {
    text-align: center;
    justify-content: center;
  }
  section.section-break {
    background: #1a1a2e;
    color: #e0e0e0;
    text-align: center;
    justify-content: center;
  }
  section.section-break h1 { color: #f0a500; }
  h1 { color: #1a1a2e; }
  h2 { color: #16213e; }
  code { font-size: 0.85rem; }
  pre { font-size: 0.78rem; }
  table { font-size: 0.9rem; }
  .columns { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; }
---

<!-- _class: title -->

# Safe Scala + Dapr
## Building distributed systems the compiler can verify

**dapr4s** — a Scala 3 library that makes Dapr effect-safe

---

## Agenda

1. **The distributed systems problem** — why runtime discipline fails
2. **Dapr** — the Distributed Application Runtime
3. **Safe Scala** — capture checking in Scala 3
4. **dapr4s** — architecture and core concepts
5. **Examples** — all 7 building blocks, live code
6. **Design patterns** — what dapr4s teaches us

---

<!-- _class: section-break -->

# Part 1
## The Problem

---

## Distributed systems are hard

Modern services need to coordinate:

- **State management** — who owns the data? what if two writes race?
- **Messaging** — at-least-once delivery, dead-lettering, replay
- **Service calls** — retries, circuit breakers, timeouts
- **Secrets** — rotation, access control, audit trails
- **Coordination** — distributed locks, leader election
- **Long-running flows** — sagas, compensations, durable timers

> Every team ends up solving the same problems, differently.

---

## The runtime discipline trap

```scala
// Typical Java/Scala pattern
class OrderService(val stateClient: DaprClient):
  def placeOrder(order: Order): OrderResult =
    val state = stateClient.getState(...)   // OK here
    ...

// But what prevents this?
val leaked = orderService.stateClient
orderService.close()
leaked.getState(...)                        // 💥 runtime error
```

**Problems:**
- Capability lifecycle tied to runtime checks
- No compiler enforcement of "use only inside scope X"
- Resource leaks discoverable only in production

---

<!-- _class: section-break -->

# Part 2
## Dapr — Distributed Application Runtime

---

## What is Dapr?

**Dapr** is an open-source, CNCF-graduated runtime (Oct 2024) that provides distributed system building blocks as a **sidecar process**.

```
  ┌─────────────┐         ┌───────────────────┐
  │  Your App   │ ◄─────► │   Dapr Sidecar    │
  │  (any lang) │  HTTP/  │                   │
  │             │  gRPC   │  State Store       │
  └─────────────┘         │  Pub/Sub           │
                          │  Service Invoc.    │
                          │  Secrets           │
                          │  Config            │
                          │  Distributed Lock  │
                          │  Actors            │
                          │  Workflows         │
                          └───────────────────┘
```

Your code never talks to Redis, Kafka, CosmosDB directly — it talks to Dapr.

---

## Dapr building blocks

| Building Block | What it does |
|---|---|
| **State** | Key-value store with ETags, transactions, bulk ops |
| **Pub/Sub** | Async messaging with at-least-once delivery |
| **Service Invocation** | Service-to-service RPC with discovery |
| **Secrets** | Unified secrets access (Vault, K8s, Azure KV, …) |
| **Configuration** | Live config with change subscriptions |
| **Distributed Lock** | Named locks with expiry and ownership |
| **Actors** | Virtual actors with per-actor state |
| **Workflows** | Durable orchestration with replay safety |
| **Bindings** | In/out integration with external systems |

> Swap the component (Redis → Cassandra) without changing your code.

---

## The Dapr sidecar model

```
  ┌─────────────────────────────────────────────────────┐
  │  Kubernetes Pod / Docker Compose Service             │
  │                                                     │
  │  ┌──────────────┐      ┌────────────────────────┐   │
  │  │  Your App    │      │     Dapr Sidecar        │   │
  │  │   :8080      │      │   HTTP  :3500           │   │
  │  │              │─────►│   gRPC  :50001          │   │
  │  │  (inbound)   │      │                         │   │
  │  │   :3000      │◄─────│  /dapr/subscribe        │   │
  │  │              │      │  POST /method/name      │   │
  │  └──────────────┘      └────────────────────────┘   │
  └─────────────────────────────────────────────────────┘
```

- App calls sidecar for **outbound** operations (publish, get state, invoke)
- Sidecar calls app for **inbound** operations (deliver message, invoke handler)
- Sidecar manages retries, tracing, auth — app stays simple

---

<!-- _class: section-break -->

# Part 3
## Safe Scala — Capture Checking

---

## Scala 3's experimental safe mode

Scala 3.9 introduces three interrelated features under `import language.experimental.safe`:

| Feature | What it enforces |
|---|---|
| **Capture Checking** | Values carry a *capture set* (`^`). A `Cap^{x}` cannot outlive `x`. |
| **Pure functions** | `A => B` is *pure* by default. Impure lambdas must declare captures. |
| **Impure-call rejection** | Calling `@rejectSafe` functions (I/O, `println`) is a **compile error**. |

> The compiler becomes your resource lifecycle manager.

---

## Capture sets in practice

```scala
def run[T](body: DaprCapability ?=> T): T = ...
//                              ^^^
//    DaprCapability is provided as a context parameter.
//    Its capture set is bound to the run() scope.

run:
  val cap = summon[DaprCapability]       // cap : DaprCapability^{cap}

  val state = cap.state(StoreName("s")) // state: StateCapability^{cap}
  //                                              ^^^^^^^^^^^^^^^^
  //                    bound to cap's lifetime — cannot escape

  state  // ← returning this is a COMPILE ERROR:
         //   StateCapability^{cap} cannot outlive the run {} block
```

The `^{cap}` annotation means "this value captures `cap`" — and therefore cannot be stored in anything that outlives `cap`.

---

## Why ExclusiveCapability?

All dapr4s capability traits extend `scala.caps.ExclusiveCapability`:

```scala
trait StateCapability extends scala.caps.ExclusiveCapability
```

This prevents two patterns the compiler would otherwise allow:

1. **Aliasing** — two variables pointing to the same capability
2. **Concurrent sharing** — passing the same capability to parallel code

```scala
// COMPILE ERROR — exclusive capabilities cannot be aliased
val a = summon[StateCapability]
val b = a  // ← rejected

// COMPILE ERROR — cannot share across thread boundary
Thread.ofVirtual().start(() => a.save(...))  // ← rejected
```

> The compiler guarantees single-threaded, scoped access to every Dapr resource.

---

## The `@assumeSafe` escape hatch

Some code cannot be capture-checked:
- Java SDK calls (Dapr's own client library)
- JSON macro derivations (upickle, circe)
- JVM thread primitives

```scala
// Library boundary — we assert safety here so user code doesn't have to
@scala.caps.assumeSafe
object Subscription:
  def apply[T: JsonCodec](pubsubName: PubSubName, topic: Topic)(
      handler: CloudEvent[T] => SubscriptionResult,   // captures DaprCap
  ): Subscription =
    // erases the capture set via .asInstanceOf[AnyRef]
    // so the returned Subscription is a plain value
    ...
```

In examples, `@assumeSafe` appears exactly once per shell module — on the upickle macro derivation. All pure business logic stays clean.

---

<!-- _class: section-break -->

# Part 4
## dapr4s — Architecture

---

## What is dapr4s?

**dapr4s** exposes every Dapr building block as a **tracked capability** verified by the Scala 3 compiler.

**Goals:**
- ✅ All 9 Dapr primitives as capability traits
- ✅ No Java types visible to user code
- ✅ Compiler-enforced resource scoping
- ✅ Pure modules with zero I/O
- ✅ Bring-your-own JSON library

**Not goals:**
- ❌ Async / Future-based API (virtual threads suffice)
- ❌ Built-in JSON codecs (your choice of upickle, circe, etc.)
- ❌ Wrapper around every Dapr option (only common paths)

---

## Two-layer architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  User Code  (import language.experimental.safe)                 │
│                                                                 │
│  DaprCapability, StateCapability, PubSubCapability, …          │
│  DaprApp, Subscription, InvocationRoute, ActorDefinition, …    │
│  JsonCodec[T], CloudEvent[T], InvocationRequest[T], …          │
│                                                                 │
│  All @assumeSafe — callable from safe mode, no Java types       │
└──────────────────────────┬──────────────────────────────────────┘
                           │  @assumeSafe boundary
┌──────────────────────────▼──────────────────────────────────────┐
│  Internal Implementation (NOT safe mode)                        │
│                                                                 │
│  *CapabilityImpl classes  — wrap Java SDK calls                 │
│  DaprAppServer            — HTTP server for inbound handlers    │
│  Dapr.run / serve         — lifecycle management               │
│                                                                 │
│  DaprClient, ActorClient, DaprWorkflowClient (Java SDK)        │
└─────────────────────────────────────────────────────────────────┘
```

---

## Capability hierarchy

```
DaprCapability
├── .state(StoreName)          → StateCapability^{this}
├── .pubsub(PubSubName)        → PubSubCapability^{this}
├── .invoker                   → ServiceInvocationCapability^{this}
├── .secrets(SecretStoreName)  → SecretsCapability^{this}
├── .config(ConfigStoreName)   → ConfigurationCapability^{this}
├── .binding(BindingName)      → BindingsCapability^{this}
├── .lock(StoreName)           → DistributedLockCapability^{this}
├── .actor(ActorType, ActorId) → ActorCapability^{this}
└── .workflow                  → WorkflowCapability^{this}
```

Each sub-capability is bound (`^{this}`) to its parent — it cannot outlive the `DaprCapability` scope.

---

## The transformer API pattern

```scala
// Open a sub-capability scope with a context function:
DaprCapability.state(StoreName("statestore")):
  // StateCapability is now in implicit scope
  StateCapability.save(StateKey("k"), "hello")
  StateCapability.get[String](StateKey("k"))
```

- `DaprCapability.state(name)` takes a **context function** `StateCapability ?=> T`
- Inside the block, `StateCapability` is a `given` — accessible anywhere
- Companion object forwarders dispatch to the implicit instance:

```scala
// You write:
StateCapability.save(key, value)

// Which desugars to:
summon[StateCapability].save(key, value)
```

No capability variable ever appears in user code.

---

## Pure modules vs Shell modules

Every example is split:

| Module | Mode | Purpose |
|---|---|---|
| `01-hello-state` | `experimental.safe` | Pure logic. Returns typed result. No I/O. |
| `01-hello-state-shell` | `experimental` only | JSON codecs, `Dapr(config).run { }`, printing. |

```
01-hello-state/
  src/hellostate/App.scala          ← pure, capture-checked
01-hello-state-shell/
  src/hellostateshell/Main.scala    ← impure shell, @assumeSafe for codecs
```

Benefits:
- Pure module is **testable without Dapr** (pass mock capabilities)
- Shell module is **thin** — just wiring and I/O
- Compiler proves the pure module has **no hidden I/O**

---

## The JsonCodec typeclass

```scala
trait JsonCodec[T]:
  def encode(value: T): String
  def decode(json: String | Null): Either[JsonDecodeException, T]
```

**Contract:**
- `encode` is total — never throws
- `decode` returns `Left` on failure — never throws
- `decode(null)` → `Left(JsonDecodeException("null input"))`
- Roundtrip: `decode(encode(v)) == Right(v)`

**No built-in instances** — you choose:

```scala
// Shell module (outside safe mode):
@scala.caps.assumeSafe
given JsonCodec[Note] = JsonCodec.fromUpickle(upickle.default.macroRW)

// Or bring circe, jsoniter, play-json, etc.
```

Codec derivation macros are inherently unsafe — they live in the shell, not the pure module.

---

## DaprApp — the handler registry

```scala
final case class DaprApp(
    subscriptions: List[Subscription]          = Nil,  // pub/sub
    invocations:   List[InvocationRoute]       = Nil,  // service invocation
    bindings:      List[BindingRoute]           = Nil,  // input bindings
    workflows:     List[Workflow]               = Nil,  // workflow classes
    activities:    List[WorkflowActivity[?, ?]] = Nil,  // activity classes
    actors:        List[ActorDefinition]        = Nil,  // actor factories
)
```

- Immutable, declarative value — describes all inbound handlers
- Returned from `Dapr(config).serve { body }` body
- Two apps can be composed: `appA ++ appB`
- Sidecar discovers subscriptions via `GET /dapr/subscribe`

---

<!-- _class: section-break -->

# Part 5
## The Examples

---

## Example 01: State Management

```scala
object HelloStateApp:
  def apply()(using DaprCapability, JsonCodec[Note]): HelloStateResult =
    DaprCapability.state(StoreName("statestore")):
      val key = StateKey("hello-note")

      // Basic save & get
      StateCapability.save(key, Note("Hello from dapr4s!", 1))
      val saved = StateCapability.get[Note](key)

      // Optimistic concurrency with ETags
      val entry = StateCapability.getWithETag[Note](key)
      val conflict: Option[ETagMismatchException] =
        (entry.value, entry.etag) match
          case (Some(n), Some(etag)) =>
            StateCapability.saveWithETag(key, n.copy(revision = n.revision + 1), etag)
          case _ => None

      // Atomic transaction: upsert two, delete one
      StateCapability.transaction(Seq(
        StateOp.UpsertOp[Note](StateKey("note-a"), Note("A", 1)),
        StateOp.UpsertOp[Note](StateKey("note-b"), Note("B", 1)),
        StateOp.DeleteOp(key),
      ))
      ...
```

---

## State API reference

| Method | What it does |
|---|---|
| `save(key, value)` | Unconditional write |
| `get[T](key)` | Fetch → `Option[T]` |
| `getWithETag[T](key)` | Fetch with server ETag → `StateEntry[T]` |
| `saveWithETag(key, v, etag)` | Conditional write → `Option[ETagMismatchException]` |
| `delete(key)` | Unconditional delete |
| `deleteWithETag(key, etag)` | Conditional delete → `Option[ETagMismatchException]` |
| `getBulk[T](keys)` | Batch fetch → `Map[StateKey, StateEntry[T]]` |
| `saveBulk[T](entries)` | Batch write |
| `transaction(ops)` | Atomic all-or-nothing batch |
| `queryState[T](query)` | Filtered query |

`saveWithETag` returns `None` on success, `Some(exception)` on conflict — no exceptions for expected races.

---

## ETag concurrency: how it works

```
   Client A                    Dapr Sidecar              State Store
      │                             │                         │
      │── getWithETag("key") ──────►│── read ────────────────►│
      │◄── {value: Note, etag: "3"}─│◄── {value, etag: "3"}──│
      │                             │                         │
      │── saveWithETag("key",       │                         │
      │     newNote, etag="3") ────►│── conditional write ───►│
      │                             │                    (etag matches)
      │◄── None (success) ──────────│◄── OK ──────────────────│
                                                              etag now "4"
```

If another client saves between your read and write:
- The store rejects the write (etag mismatch)
- dapr4s returns `Some(ETagMismatchException)` — no exception thrown
- You retry the read-modify-write cycle

---

## Example 02: Secrets & Configuration

```scala
def readSecrets()(using SecretsCapability): Map[SecretKey, SecretValue] =
  SecretsCapability.getBulk()                     // all secrets in store

def readConfig()(using ConfigurationCapability): Map[ConfigKey, ConfigItem] =
  ConfigurationCapability.get(configKeys)         // specific config keys

// Pure module stays clean — keys defined here, not in the shell
val configKeys: Seq[ConfigKey] = Seq(
  ConfigKey("greeting"),
  ConfigKey("max-retries"),
)

// Subscriptions (impure — live in the shell):
//   ConfigurationCapability.subscribe(keys)(onChange: ConfigUpdate => Unit)
//   Returns AutoCloseable — close() to stop
```

**Key insight:** Reading secrets/config is pure. *Subscribing* to config changes is impure (background thread) — it lives in the shell module.

---

## Example 03: Pub/Sub

```scala
// Publisher
object PublisherApp:
  def apply()(using DaprCapability, JsonCodec[Message]): Unit =
    DaprCapability.pubsub(PubSubName("pubsub")):
      for i <- 1 to 5 do
        PubSubCapability.publish(
          Topic("hello-topic"),
          Message(from = "publisher", text = "hello world", sequenceNo = i),
        )

// Subscriber — handler captures PubSubCapability to republish
def onMessage(event: CloudEvent[Message])(using PubSubCapability, JsonCodec[Message]): SubscriptionResult =
  val msg = event.data
  PubSubCapability.publish(Topic("hello-replies"), msg.copy(from = "subscriber"))
  SubscriptionResult.Success
```

---

## CloudEvent envelope

Every inbound pub/sub message is wrapped in a `CloudEvent[T]`:

```scala
case class CloudEvent[T](
  id:              String,
  source:          String,
  specVersion:     String,
  eventType:       String,
  topic:           Topic,
  pubSubName:      PubSubName,
  dataContentType: String,
  data:            T,          // ← your deserialized payload
)
```

Handler returns `SubscriptionResult`:

| Value | Meaning |
|---|---|
| `SubscriptionResult.Success` | ACK — message consumed |
| `SubscriptionResult.Retry` | NAK — redeliver later |
| `SubscriptionResult.Drop` | Silently discard |

---

## Declaring a subscription

```scala
object SubscriberApp:
  def apply()(using DaprCapability, JsonCodec[Message]): DaprApp =
    DaprCapability.pubsub(PubSubComponent):
      DaprApp(subscriptions =
        List(
          // Default route: "/" + topic.value
          Subscription[Message](PubSubName("pubsub"), Topic("hello-topic"))(onMessage),

          // Custom route:
          Subscription[Message](PubSubName("pubsub"), Topic("orders"), Route("/order-handler"))(handle),
        ),
      )
```

The `Subscription` factory uses `@assumeSafe` internally to erase the handler's capture set — so a `List[Subscription]` is a plain, capture-free value the server can store safely.

---

## Example 04: Service Invocation

```scala
// ── Callee: declare methods the sidecar can route to ──────────────────────

object CalleeApp:
  def apply()(using DaprCapability, /* codecs... */): DaprApp =
    DaprCapability.state(StatStore):
      DaprApp(invocations =
        List(
          InvocationRoute[GreetRequest, GreetResponse](MethodName("greet"))(greet),
          InvocationRoute[Unit, StatsResponse](MethodName("stats"))(_ => stats()),
        ),
      )

// ── Caller: invoke another service ────────────────────────────────────────

object CallerApp:
  def apply()(using DaprCapability, /* codecs... */): CallerResult =
    DaprCapability.invoker:
      ServiceInvocationCapability.invoke[GreetRequest](
        AppId("greeting-service"),
        MethodName("greet"),
        GreetRequest("Alice", "en"),
        HttpMethod.Post,
      )[GreetResponse]
```

---

## InvocationRequest — access the HTTP verb

Sometimes your handler needs to know which HTTP method was used:

```scala
// Simple overload — receives only the decoded body
InvocationRoute[GreetRequest, GreetResponse](MethodName("greet"))(greet)

// Rich overload — receives the full envelope
InvocationRoute.withRequest[GreetRequest, GreetResponse](MethodName("greet")): req =>
  val verb    = req.httpMethod   // HttpMethod.Post, HttpMethod.Get, …
  val method  = req.methodName   // MethodName("greet")
  val body    = req.data         // GreetRequest(…)
  greet(body)
```

```scala
case class InvocationRequest[T](
  methodName: MethodName,
  httpMethod: HttpMethod,
  data:       T,
)
```

Why two overloads? Scala 3's overload resolution cannot distinguish `Req => Resp` from `InvocationRequest[Req] => Resp` when the lambda is untyped — hence the distinct name `withRequest`.

---

## Example 05: Distributed Lock

```scala
val resourceId = LockResourceId("shared-counter")
val owner      = LockOwner("worker-1")

DaprCapability.lock(StoreName("lockstore")):
  val acquired = DistributedLockCapability.tryLock(resourceId, owner, expirySeconds = 30)

  if acquired then
    try
      // critical section — only one worker enters here
      val counter = readCounter()
      saveCounter(counter + 1)
    finally
      val status = DistributedLockCapability.unlock(resourceId, owner)
      // status: UnlockStatus.Success | LockNotFound | InternalError
```

**Semantics:**
- Same owner can re-acquire the same resource (idempotent)
- Different owner gets `false` — never blocks
- Lock expires automatically after `expirySeconds`

---

## Example 06: Actors

```scala
def counterActorDefinition(/*config...*/): ActorDefinition =
  ActorDefinition(ActorType("CounterActor")): (id, ctx) =>
    given ActorContext = ctx   // ← ExclusiveCapability, cannot escape
    ActorRoutes(
      methods = List(
        ActorMethodRoute[IncrBy, CounterState](MethodName("increment"))(increment),
        ActorMethodRoute[Unit, CounterState](MethodName("get"))(_ => readState),
        ActorMethodRoute[Unit, CounterState](MethodName("startTimer")): _ =>
          ActorContext.registerTimer(AutoTimer, IncrBy(1), tickInterval, tickDelay)
          readState,
        ActorMethodRoute[Unit, CounterState](MethodName("scheduleReset")): _ =>
          ActorContext.registerReminder(ResetReminder, "time to reset", reminderDelay, None)
          readState,
      ),
      timers    = List(ActorTimerRoute[IncrBy](AutoTimer)(onAutoTick)),
      reminders = List(ActorReminderRoute[String](ResetReminder)(onReset)),
    )
```

---

## Actors: timers vs reminders

| | Timer | Reminder |
|---|---|---|
| **Persistence** | Non-persistent | Persistent across restarts |
| **Survival** | Lost on actor deactivation | Reactivates actor to deliver |
| **Use for** | Rate-based work, polling | Business deadlines, SLAs |
| **API** | `registerTimer` / `unregisterTimer` | `registerReminder` / `unregisterReminder` |

```scala
// Per-actor state: isolated to each actor instance
def readState(using ActorContext, JsonCodec[Int]): CounterState =
  CounterState(
    count            = ActorContext.get[Int](StateKey("count")).getOrElse(0),
    totalIncrements  = ActorContext.get[Int](StateKey("total")).getOrElse(0),
  )
```

`ActorContext` is an `ExclusiveCapability` — it cannot be captured in a lambda that outlives the handler invocation.

---

## Calling an actor (driver side)

```scala
// From client code:
DaprCapability.actor(ActorType("CounterActor"), ActorId("counter-1")):
  // increment by 5
  ActorCapability.invoke[IncrBy](MethodName("increment"), IncrBy(5))[CounterState]
  // → CounterState(count = 5, totalIncrements = 1)

  // get current state
  ActorCapability.invoke[CounterState](MethodName("get"))
  // → CounterState(count = 5, totalIncrements = 1)
```

Actor instances are **virtual** — the runtime activates them on demand and deactivates idle ones. State persists in the configured state store between activations.

---

## Example 07: Workflows — Durable Orchestration

```scala
class OrderProcessingWorkflow(using /* codecs… */) extends Workflow:
  def run(using WorkflowContext): Unit =
    val order = WorkflowContext.getInput[OrderRequest].getOrElse(throw RuntimeException("no input"))

    // Schedule activities — returns Task[Result]
    val reservation = WorkflowContext.callActivity[ReserveInventory](order).await()
    if !reservation.reserved then
      WorkflowContext.complete(OrderResult(false, "out of stock"))
    else
      val payment = WorkflowContext.callActivity[ChargePayment](order).await()
      if !payment.charged then
        WorkflowContext.callActivity[CancelReservation](reservation).await()   // compensate
        WorkflowContext.complete(OrderResult(false, "payment declined"))
      else
        val shipment = WorkflowContext.callActivity[DispatchShipment](order).await()
        WorkflowContext.complete(OrderResult(true, s"shipped: ${shipment.trackingId}"))
```

---

## Workflow activities

```scala
class ReserveInventory(using JsonCodec[OrderRequest], JsonCodec[ReservationResult])
    extends WorkflowActivity[OrderRequest, ReservationResult]:
  def execute(req: OrderRequest): ReservationResult =
    // Pure computation — call external APIs, databases, etc.
    val ok = req.quantity <= 5
    ReservationResult(ok, if ok then s"RES-${req.orderId}" else "")
```

**Activities are the I/O boundary:**
- The workflow orchestrator calls activities, never external services directly
- Activity results are persisted — on replay, the cached result is returned
- Activities may be retried automatically on transient failures

**Workflow must be deterministic:**
- No random numbers (use `WorkflowContext.new_uuid()`)
- No wall-clock time (use `WorkflowContext.createTimer(duration)`)
- No direct I/O (`println`, DB calls, HTTP) — put these in activities

---

## The Saga pattern in action

```
OrderProcessingWorkflow
│
├── ReserveInventory ─────────────────► OK: reserved = true
│
├── ChargePayment ────────────────────► OK: charged = true
│                  FAILURE (charged=false):
│                    CancelReservation  ← compensation
│                    complete(FAILED)
│
├── DispatchShipment ─────────────────► OK: dispatched = true
│                  FAILURE:
│                    RefundPayment      ← compensation
│                    CancelReservation  ← compensation
│                    complete(FAILED)
│
└── complete(SUCCESS, trackingId)
```

Each step compensates all prior steps on failure — this is the **Saga pattern** for distributed transactions.

---

## Workflow control API

```scala
// Start a workflow
val id: WorkflowInstanceId = WorkflowCapability.start(
  WorkflowName("OrderProcessingWorkflow"),
  order,                           // serialized as input
)

// Wait for completion (blocks virtual thread)
val snapshot: Option[WorkflowSnapshot] =
  WorkflowCapability.waitForCompletion(id, timeout = 30.seconds)

// Poll without blocking
val status: WorkflowSnapshot = WorkflowCapability.getStatus(id)
// status.status: WorkflowStatus.Running | Completed | Failed | Terminated | …

// Control
WorkflowCapability.pause(id)
WorkflowCapability.resume(id)
WorkflowCapability.terminate(id)
```

---

## Composing DaprApps

`DaprApp` is a plain case class — combine with `++`:

```scala
// Pure module A — owns subscriptions
def subscriberModule()(using DaprCapability, /* codecs */): DaprApp =
  DaprApp(subscriptions = List(...))

// Pure module B — owns invocation routes
def invocationModule()(using DaprCapability, /* codecs */): DaprApp =
  DaprApp(invocations = List(...))

// Shell: compose and serve
Dapr(config).serve:
  subscriberModule() ++ invocationModule() ++ workflowModule()
```

Your microservice can expose **all Dapr capabilities simultaneously** — each registered handler is routed by path.

---

<!-- _class: section-break -->

# Part 6
## Design Patterns

---

## Opaque domain types

Every identifier in dapr4s is a distinct opaque type — not a raw `String`:

```scala
opaque type StoreName   = String  ; object StoreName   { def apply(v: String): StoreName   = v }
opaque type StateKey    = String  ; object StateKey    { def apply(v: String): StateKey    = v }
opaque type PubSubName  = String  ; object PubSubName  { def apply(v: String): PubSubName  = v }
opaque type Topic       = String  ; object Topic       { def apply(v: String): Topic       = v }
opaque type AppId       = String  ; object AppId       { def apply(v: String): AppId       = v }
opaque type MethodName  = String  ; object MethodName  { def apply(v: String): MethodName  = v }
// … and 15+ more
```

**Benefits:**
- Can't accidentally pass a `Topic` where a `PubSubName` is expected
- Zero runtime overhead — erases to `String` at the JVM level
- Factory objects enforce invariants (non-empty checks) at construction

---

## The companion object forwarder pattern

```scala
// The trait — holds the capability
trait StateCapability extends scala.caps.ExclusiveCapability:
  def get[T](key: StateKey)(using JsonCodec[T]): Option[T]
  def save[T](key: StateKey, value: T)(using JsonCodec[T]): Unit
  ...

// The companion — dispatches to the implicit instance
object StateCapability:
  def get[T](key: StateKey)(using cap: StateCapability, codec: JsonCodec[T]): Option[T] =
    cap.get(key)
  def save[T](key: StateKey, value: T)(using cap: StateCapability, codec: JsonCodec[T]): Unit =
    cap.save(key, value)
  ...
```

**Usage:**
```scala
// No capability variable ever appears in business logic
def myLogic()(using StateCapability, JsonCodec[Note]): Note =
  StateCapability.save(key, note)
  StateCapability.get[Note](key).getOrElse(throw ...)
```

---

## Testing without Dapr

Pure modules are functions from capabilities to results — easy to test:

```scala
class FakeStateCapability extends StateCapability:
  private val store = mutable.Map[StateKey, String]()
  def get[T](key: StateKey)(using JsonCodec[T]): Option[T] = store.get(key).flatMap(codec.decode(_).toOption)
  def save[T](key: StateKey, v: T)(using JsonCodec[T]): Unit = store(key) = codec.encode(v)
  // ... other methods

// Test pure module without any Dapr sidecar:
test("hello state"):
  val fakeState: StateCapability = FakeStateCapability()
  given DaprCapability = FakeDaprCapability(state = fakeState)
  val result = HelloStateApp()
  assertEquals(result.saved, Some(Note("Hello from dapr4s!", 1)))
```

> Integration tests (with a real Dapr sidecar) live in `test/integration/` and run via Testcontainers.

---

## `@assumeSafe` — the contract

The `@assumeSafe` annotation is a **promise**, not a bypass:

```
Who writes it?    Library authors (dapr4s internals), shell modules (codec derivation)
Who reads it?     The Scala 3 compiler — treats the annotated code as if it were safe
What it means?    "I have manually verified that this code upholds the safety contract"

What it does NOT mean:
  - "This code is unsafe, we're ignoring it"
  - "This code is untested"
  - "Any caller is absolved of responsibility"
```

**In dapr4s:**
- Applied to every factory object (`Subscription`, `InvocationRoute`, `BindingRoute`, etc.)
- Applied to the Java SDK wrapper classes
- Applied to `JsonCodec` trait (crossing macro boundary)

**In user code:**
- Applied only to codec derivation in shell modules
- Never appears in pure modules

---

## Entry points

```scala
// One-shot request/response:
Dapr(config).run:
  val cap = summon[DaprCapability]
  DaprCapability.state(StoreName("statestore")):
    StateCapability.save(StateKey("k"), "hello")

// Long-running HTTP server (blocks until JVM shuts down):
Dapr(config).serve:
  DaprApp(
    subscriptions = List(Subscription[Msg](pubsub, topic)(handler)),
    invocations   = List(InvocationRoute[Req, Resp](method)(handler)),
    actors        = List(counterActorDefinition()),
    workflows     = List(new OrderProcessingWorkflow),
    activities    = List(new ReserveInventory, new ChargePayment, ...),
  )
```

```scala
// DaprConfig — all defaults are sensible for local development:
DaprConfig(
  sidecar   = SidecarConfig(httpEndpoint = URI("http://localhost:3500"), ...),
  appServer = AppServerConfig(port = Port(3000), shutdownGrace = 5.seconds, ...),
  actors    = ActorConfig(actorIdleTimeout = 1.hour, ...),
)
```

---

<!-- _class: section-break -->

# Part 7
## Summary

---

## What dapr4s gives you

| Traditional approach | With dapr4s |
|---|---|
| Runtime checks for resource lifecycle | Compiler-enforced via `^{cap}` |
| Any code can call any DAPR operation | Effects declared in type signatures |
| Java SDK types leak everywhere | Zero Java types in user code |
| Unit tests require mocking the SDK | Test pure functions directly |
| JSON library chosen by the library | Bring your own |
| One global DAPR client | Per-scope, scoped capabilities |

---

## Key takeaways

1. **Dapr** provides 9 distributed system building blocks — you swap the component, not the code
2. **Safe Scala** makes resource scoping a compile-time guarantee, not a convention
3. **dapr4s** wraps Dapr in capture-checked capability traits — the compiler proves your code can't misuse them
4. **Pure modules** separate business logic from I/O — no capability variable ever leaks
5. **ExclusiveCapability** prevents aliasing and concurrent sharing at compile time
6. **DaprApp** is a composable, declarative registry — `appA ++ appB` for modular services
7. **Saga pattern** in workflows gives you distributed transactions without a coordinator
8. **Opaque types** eliminate stringly-typed mistakes with zero runtime cost

---

## Project status & getting started

**Requirements:**
- Scala 3.9.0-RC1 nightly (`3.9.0-RC1-bin-YYYYMMDD-*-NIGHTLY`)
- JDK 21+ (virtual threads for `Dapr.run`)
- Dapr CLI + sidecar

**Quick start:**
```scala
//> using scala "3.9.0-RC1-bin-20260501-0c8c581-NIGHTLY"
//> using dep "com.github.sideeffffect::dapr4s:0.1.0-SNAPSHOT"
//> using option "-language:experimental.safe"
//> using option "-language:experimental"
```

**Examples:**
```bash
git clone https://github.com/sideeffffect/dapr4s-examples
cd dapr4s-examples
./mill e2e.testForked    # runs all 7 examples end-to-end with Testcontainers
```

---

## Resources

- **dapr4s library** — `github.com/sideeffffect/dapr4s`
- **dapr4s examples** — `github.com/sideeffffect/dapr4s-examples`
- **Dapr documentation** — `docs.dapr.io`
- **Scala Capture Checking** — `docs.scala-lang.org/scala3/reference/experimental/cc.html`
- **SPEC.allium** — formal behavioural specification (Allium v3 DSL)

**Safe Scala design principle:**

> *"Instead of relying on runtime discipline ('don't use the client after it's closed'), let the compiler enforce it."*

---

<!-- _class: title -->

# Thank you

**Questions?**

---
*dapr4s — Safe Scala wrappers for Dapr*
*All 7 examples: `github.com/sideeffffect/dapr4s-examples`*
