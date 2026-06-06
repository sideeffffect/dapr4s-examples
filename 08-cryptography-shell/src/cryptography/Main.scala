package cryptography

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
  println("=== 10 cryptography (localstorage RSA key) ===\n")
  Dapr(daprConfigFromEnv()).run:
    val r = CryptographyDemoApp()
    println(s"plaintext:        ${r.plaintext}")
    println(s"ciphertext bytes: ${r.cipherSize}")
    println(s"decrypted:        ${r.decrypted}  ${if r.decrypted == r.plaintext then "✓" else "✗"}")
    println(s"raw-bytes round-trip: ${r.bytesRoundTrip}  ${if r.bytesRoundTrip then "✓" else "✗"}")
  println("\n[cryptography] done.")
