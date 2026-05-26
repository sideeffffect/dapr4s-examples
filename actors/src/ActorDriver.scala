package actors

import dapr4s.*

// ── Driver ────────────────────────────────────────────────────────────────────
// ActorCapability is scoped per (actorType, actorId) pair.
// DaprCapability.actor(type, id) { ... } gives a capability that is valid only
// within that block.  Calling it outside would be a compile error.

@main def actorDriver(): Unit =
  println("=== 06 actors: CounterActor driver ===\n")

  DaprRuntime.run(DaprRuntimeConfig()):

    val id = ActorId("counter-1")

    // ── Initial state ──────────────────────────────────────────────────────
    DaprCapability.actor(ActorTypeName, id):
      val state = ActorCapability.invoke[CounterState](MethodName("get"))
      println(s"initial state: $state")

    // ── Increment several times ────────────────────────────────────────────
    for i <- 1 to 3 do
      DaprCapability.actor(ActorTypeName, id):
        val result = ActorCapability.invoke[IncrBy](MethodName("increment"), IncrBy(i))[CounterState]
        println(s"after increment($i): $result")
      Thread.sleep(300)

    // ── Start the auto-tick timer ──────────────────────────────────────────
    DaprCapability.actor(ActorTypeName, id):
      val state = ActorCapability.invoke[CounterState](MethodName("startTimer"))
      println(s"timer started, state: $state")

    println("Waiting 10 s for timer ticks...")
    Thread.sleep(10_000)

    // ── Read final state ───────────────────────────────────────────────────
    DaprCapability.actor(ActorTypeName, id):
      val state = ActorCapability.invoke[CounterState](MethodName("get"))
      println(s"final state: $state")

  println("\n[actor-driver] done.")
