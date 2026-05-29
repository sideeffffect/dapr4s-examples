package hellopubsub

import dapr4s.*

val PubSubComponent = PubSubName("pubsub")
val MessageTopic = Topic("hello-topic")

case class Message(from: String, text: String, sequenceNo: Int)

// ── Capture-checked pure module ───────────────────────────────────────────────
// The subscription handler captures PubSubCapability; the compiler tracks this
// capture when the handler is passed to Subscription[Message].  The @assumeSafe
// boundary inside Subscription.apply erases the capture set, so the returned
// DaprApp is a plain value.  JsonCodec[Message] is passed from the shell.
// ─────────────────────────────────────────────────────────────────────────────

def onMessage(event: CloudEvent[Message])(using PubSubCapability, JsonCodec[Message]): SubscriptionResult =
  val msg = event.data
  PubSubCapability.publish(Topic("hello-replies"), msg.copy(from = "subscriber"))
  SubscriptionResult.Success

def subscriberApp()(using DaprCapability, JsonCodec[Message]): DaprApp =
  DaprCapability.pubsub(PubSubComponent):
    DaprApp(subscriptions =
      List(
        Subscription[Message](PubSubComponent, MessageTopic)(onMessage),
      ),
    )

def publisherApp()(using DaprCapability, JsonCodec[Message]): Unit =
  DaprCapability.pubsub(PubSubComponent):
    for i <- 1 to 5 do
      PubSubCapability.publish(MessageTopic, Message(from = "publisher", text = "hello world", sequenceNo = i))
