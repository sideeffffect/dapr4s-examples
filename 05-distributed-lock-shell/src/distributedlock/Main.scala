package distributedlock

import dapr4s.*

// ── Impure shell ──────────────────────────────────────────────────────────────

private def upickleCodec[T: upickle.default.ReadWriter]: JsonCodec[T] = new JsonCodec[T]:
  def encode(value: T): String = upickle.default.write(value)
  def decode(json: String | Null): Either[JsonDecodeException, T] =
    if json == null then Left(JsonDecodeException("null input"))
    else
      try Right(upickle.default.read[T](json))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

private given JsonCodec[Int] = upickleCodec

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
  println("=== 05 distributed-lock ===\n")
  Dapr(daprConfigFromEnv()).run:
    val r = distributedLockApp()
    println(s"Counter after ${r.expected} sequential workers: ${r.finalCounter}  ${
        if r.finalCounter == r.expected then "✓" else "✗"
      }")
    println()
    println("--- Double-acquire attempt ---")
    println(s"process-B tryLock while A holds it: ${r.secondAcquire}  (expected false)  ${
        if !r.secondAcquire then "✓" else "✗"
      }")
    println(
      s"process-B tryLock after A releases: ${r.afterRelease}  (expected true)   ${if r.afterRelease then "✓" else "✗"}",
    )
  println("\n[distributed-lock] done.")
