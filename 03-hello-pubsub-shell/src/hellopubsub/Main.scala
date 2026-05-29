package hellopubsub

import dapr4s.*

// ── Impure shell ──────────────────────────────────────────────────────────────

@scala.caps.assumeSafe
given upickle.default.ReadWriter[Message] = upickle.default.macroRW

private def daprConfigFromEnv(defaultAppPort: Int): DaprConfig =
  val appPort = sys.env.getOrElse("APP_PORT", defaultAppPort.toString).toInt
  val http = sys.env.getOrElse("DAPR_HTTP_PORT", "3500").toInt
  val grpc = sys.env.getOrElse("DAPR_GRPC_PORT", "50001").toInt
  DaprConfig(
    sidecar = SidecarConfig(
      httpEndpoint = java.net.URI.create(s"http://localhost:$http"),
      grpcEndpoint = java.net.URI.create(s"http://localhost:$grpc"),
      grpcTlsInsecure = false,
    ),
    appServer = AppServerConfig(port = DaprPort(appPort)),
  )

@main def subscriber(): Unit =
  val config = daprConfigFromEnv(defaultAppPort = 8083)
  println(s"=== 03 hello-pubsub: subscriber on port ${config.appServer.port} ===\n")
  Dapr(config).serve:
    subscriberApp()

@main def publisher(): Unit =
  println("=== 03 hello-pubsub: publisher ===\n")
  Dapr(daprConfigFromEnv(defaultAppPort = 8083)).run:
    println("Publishing 5 messages to hello-topic...")
    publisherApp()
  println("[publisher] done.")
