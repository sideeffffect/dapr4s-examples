package bindings

import dapr4s.*

// ── Capture-checked pure module ───────────────────────────────────────────────
// Bindings has two directions, both shown here against two different components:
//
//   • OUTPUT binding (app → external) — BindingsCapability.invoke / invokeOneWay.
//     We talk to TWO external systems:
//       - `jsonplaceholder`: an HTTP binding pointed at https://jsonplaceholder.typicode.com.
//         `invoke(get,  path=/posts/N)`  fetches a Post; `invoke(post, /posts)` creates one.
//       - `orders-queue`:    a Kafka binding used in the *output* direction to enqueue a PostRef.
//
//   • INPUT binding (external → app) — a BindingRoute the sidecar POSTs to when an
//     external event arrives. The same `orders-queue` Kafka binding is *bidirectional*:
//     a message produced above is delivered back here, and the handler then uses the
//     `jsonplaceholder` output binding to fetch the referenced Post and persist it.
//
// End-to-end flow (one app, no external tooling needed to drive it):
//
//   InvocationRoute /enqueue ──▶ orders-queue.invokeOneWay   [OUTPUT to Kafka]
//                                        │
//                                   Dapr / Kafka
//                                        ▼
//   BindingRoute(orders-queue) ◀── sidecar POST            [INPUT from Kafka]
//        └─▶ jsonplaceholder.invoke(get /posts/N)[Post]    [OUTPUT to HTTP API]
//        └─▶ StateCapability.save(post-N, fetched)
//
//   InvocationRoute /create  ──▶ jsonplaceholder.invoke(post /posts)[Post]  [OUTPUT to HTTP API]
//
// WHY two explicit `val`s instead of the `DaprCapability.binding(name){…}` transformer:
// both bindings are the SAME type (`BindingsCapability`), so they cannot both sit in
// implicit scope as `given`s (that would be an ambiguous implicit). We use the direct
// factory style — `cap.binding(name)` returns a `BindingsCapability` we name explicitly —
// and call `.invoke` on each by name. The route lambdas capture these values; the
// @assumeSafe AnyRef-erasure inside InvocationRoute/BindingRoute drops the capture set.
// StateCapability, of which there is only one, stays an implicit via the state transformer.
// ─────────────────────────────────────────────────────────────────────────────

val StateStore = StoreName("statestore")
val OrdersQueue = BindingName("orders-queue") // Kafka, bidirectional (input + output)
val JsonPlaceholder = BindingName("jsonplaceholder") // HTTP, output only

// jsonplaceholder.typicode.com resource shapes.
final case class Post(userId: Int, id: Int, title: String, body: String)
final case class NewPost(title: String, body: String, userId: Int)

// The message we put on / take off the Kafka queue: "go fetch post #postId".
final case class PostRef(postId: Int)

def postKey(id: Int): StateKey = StateKey(s"post-$id")

// HTTP-binding metadata: the `path` is appended to the binding's base `url`.
private def pathMeta(path: String): Map[MetadataKey, MetadataValue] =
  Map(MetadataKey("path") -> MetadataValue(path))

object BindingsExampleApp:
  def apply()(using
      DaprCapability,
      JsonCodec[Post],
      JsonCodec[NewPost],
      JsonCodec[PostRef],
      JsonCodec[String],
  ): DaprApp =
    val cap = summon[DaprCapability]
    val ordersQueue = cap.binding(OrdersQueue) // produce side of the Kafka binding
    val jsonPlaceholder = cap.binding(JsonPlaceholder) // the HTTP API binding

    DaprCapability.state(StateStore):
      DaprApp(
        invocations = List(
          // OUTPUT (HTTP, with a request body): create a post on jsonplaceholder.
          InvocationRoute[NewPost, Post](MethodName("create")): newPost =>
            jsonPlaceholder
              .invoke(BindingOperation("post"), newPost, pathMeta("/posts"))[Post]
              .getOrElse(throw RuntimeException("jsonplaceholder returned no body for create")),
          // OUTPUT (Kafka, fire-and-forget): enqueue a reference; the BindingRoute below
          // receives it back from Kafka and does the fetch.
          InvocationRoute[PostRef, String](MethodName("enqueue")): ref =>
            ordersQueue.invokeOneWay(BindingOperation("create"), ref)
            s"enqueued post ${ref.postId}",
        ),
        bindings = List(
          // INPUT (Kafka): triggered when a message lands on the queue. Fetches the
          // referenced post via the HTTP output binding and persists it to state.
          BindingRoute[PostRef](OrdersQueue): ref =>
            jsonPlaceholder
              .invoke(BindingOperation("get"), "", pathMeta(s"/posts/${ref.postId}"))[Post]
              .foreach(post => StateCapability.save(postKey(post.id), post)),
        ),
      )
