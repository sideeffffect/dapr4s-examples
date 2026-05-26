package hellopubsub

import dapr4s.*

// ── Safe Scala guarantee ────────────────────────────────────────────────────
// The subscription handler `onMessage` is declared as a method taking
// `PubSubCapability` as a `using` context parameter.  When it is passed to
// `Subscription[Message](...)`, its capture of PubSubCapability is visible
// in the closure's type.  The compiler verifies that PubSubCapability is still
// live at the point where the lambda is formed.
// ─────────────────────────────────────────────────────────────────────────────

val PubSubComponent = PubSubName("pubsub")
val MessageTopic    = Topic("hello-topic")

case class Message(from: String, text: String, sequenceNo: Int)

@scala.caps.assumeSafe
object Message:
  given upickle.default.ReadWriter[Message] = upickle.default.macroRW

// ── Subscriber ───────────────────────────────────────────────────────────────
// Starts an HTTP server that Dapr calls for every delivered message.

@main def subscriber(): Unit =
  val port   = sys.env.getOrElse("APP_PORT", "8083").toInt
  val config = DaprRuntimeConfig(appServer = AppServerConfig(port = DaprPort(port)))
  println(s"=== 03 hello-pubsub: subscriber on port $port ===\n")

  DaprRuntime.serve(config):
    DaprCapability.pubsub(PubSubComponent):

      DaprApp(subscriptions = List(
        // The handler uses PubSubCapability — captured in the closure.
        // It echoes each message back to a "replies" topic, demonstrating
        // that capability use inside a handler is safe and scoped.
        Subscription[Message](PubSubComponent, MessageTopic): event =>
          val msg = event.data
          println(s"[subscriber] received #${msg.sequenceNo}: '${msg.text}' from ${msg.from}")
          PubSubCapability.publish(Topic("hello-replies"), msg.copy(from = "subscriber"))
          SubscriptionResult.Success
      ))

// ── Publisher ─────────────────────────────────────────────────────────────────
// Short-lived: publishes 5 messages then exits.

@main def publisher(): Unit =
  println("=== 03 hello-pubsub: publisher ===\n")

  DaprRuntime.run(DaprRuntimeConfig()):
    DaprCapability.pubsub(PubSubComponent):

      for i <- 1 to 5 do
        val msg = Message(from = "publisher", text = s"hello world", sequenceNo = i)
        PubSubCapability.publish(MessageTopic, msg)
        println(s"[publisher] sent #$i")
        Thread.sleep(500)

  println("[publisher] done.")
