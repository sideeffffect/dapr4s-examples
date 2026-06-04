package conversation

import dapr4s.*

// ── Impure shell ──────────────────────────────────────────────────────────────

private def daprConfigFromEnv(): DaprConfig =
  val http = sys.env.getOrElse("DAPR_HTTP_PORT", "3500").toInt
  val grpc = sys.env.getOrElse("DAPR_GRPC_PORT", "50001").toInt
  DaprConfig(sidecar =
    SidecarConfig(
      httpEndpoint = java.net.URI.create(s"http://localhost:$http"),
      grpcEndpoint = java.net.URI.create(s"http://localhost:$grpc"),
      grpcTlsInsecure = false,
    ),
  )

@main def run(): Unit =
  println("=== 12 conversation (echo component) ===\n")
  Dapr(daprConfigFromEnv()).run:
    val r = ConversationDemoApp()
    println(s"converse(\"hello world\"): ${r.single}")
    println(s"converseMany(alpha, beta, gamma): ${r.many.mkString(" | ")}")
    println(s"converseAlpha2(user: ping) -> ${r.chatReply.getOrElse("<none>")}")
  println("\n[conversation] done.")
