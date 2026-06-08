package secretsconfig

import dapr4s.*

// ── Impure shell ──────────────────────────────────────────────────────────────
// Starts the Dapr runtime, calls the pure read functions, prints results, and
// demonstrates the impure config-subscription API (callback + Thread.sleep).
// ─────────────────────────────────────────────────────────────────────────────

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
  println("=== 02 secrets-config: secrets and live configuration ===\n")
  Dapr(daprConfigFromEnv()).run:

    val (apiKey, allKeys) = readSecrets()
    println(s"MY_API_KEY   = ${apiKey.map(_.value).getOrElse("<not set>")}")
    println(s"all secrets: ${allKeys.mkString(", ")}\n")

    val items = readConfig()
    items.foreach: (k, item) =>
      println(s"config ${k.value} = ${item.value}  (version: ${item.version.value})")

    println("\nSubscribing to live config changes for 'greeting'...")
    println("(Update the key via Redis CLI or `dapr publish` to see changes.)\n")
    DaprCapability.configuration(ConfigurationStoreName("configstore")):
      val sub = ConfigurationCapability.subscribe(Seq(ConfigurationKey("greeting")), Map.empty): update =>
        val changes = update.items.map((k, v) => s"${k.value}=${v.value}").mkString(", ")
        println(s"[config update] $changes")
      Thread.sleep(15_000)
      sub.close()
      println("Unsubscribed.")
