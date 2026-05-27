package hellopubsub

import dapr4s.*

// ── Impure shell ──────────────────────────────────────────────────────────────

@scala.caps.assumeSafe
given upickle.default.ReadWriter[Message] = upickle.default.macroRW

@main def subscriber(): Unit =
  val port   = sys.env.getOrElse("APP_PORT", "8083").toInt
  val config = DaprRuntimeConfig(appServer = AppServerConfig(port = DaprPort(port)))
  println(s"=== 03 hello-pubsub: subscriber on port $port ===\n")
  DaprRuntime.serve(config):
    subscriberApp()

@main def publisher(): Unit =
  println("=== 03 hello-pubsub: publisher ===\n")
  DaprRuntime.run(DaprRuntimeConfig()):
    println("Publishing 5 messages to hello-topic...")
    publisherApp()
  println("[publisher] done.")
