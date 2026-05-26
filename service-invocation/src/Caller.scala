package serviceinvocation

import dapr4s.*

// ── Caller ────────────────────────────────────────────────────────────────────
// ServiceInvocationCapability is scoped to the DaprRuntime.run block.
// There is no way to call the remote service outside that block — the
// capability ceases to exist at the closing brace.

@main def caller(): Unit =
  println("=== 04 service-invocation: caller ===\n")

  DaprRuntime.run(DaprRuntimeConfig()):
    DaprCapability.invoker:

      val target = AppId("greeting-service")

      val greetings = List(
        GreetRequest("Alice", "en"),
        GreetRequest("Bob",   "es"),
        GreetRequest("Carol", "fr"),
        GreetRequest("Dave",  "de"),
        GreetRequest("Eve",   "jp"),
      )

      for req <- greetings do
        val resp = ServiceInvocationCapability.invoke[GreetRequest](
          target, MethodName("greet"), req, HttpMethod.Post,
        )[GreetResponse]
        println(s"${resp.greeting}  (from: ${resp.from})")
        Thread.sleep(200)

      println()
      val stats = ServiceInvocationCapability.invoke[StatsResponse](
        target, MethodName("stats"),
      )
      println(s"Total requests: ${stats.totalRequests}")
      println(s"Languages seen: ${stats.languages.mkString(", ")}")

  println("\n[caller] done.")
