package secretsconfig

import dapr4s.*

// ── Safe Scala guarantee ────────────────────────────────────────────────────
// Both SecretsCapability and ConfigurationCapability are acquired and released
// by their respective DaprCapability.secrets / DaprCapability.config blocks.
// The subscribe callback for live config updates captures ConfigurationCapability
// in its closure — the capture is visible in the closure's type and the
// compiler guarantees the capability outlives the subscription.
// ─────────────────────────────────────────────────────────────────────────────

@main def run(): Unit =
  println("=== 02 secrets-config: secrets and live configuration ===\n")

  // Set up: export MY_API_KEY=s3cr3t before running.
  // The secretstores.local.env component maps secret names to env-var names.

  DaprRuntime.run(DaprRuntimeConfig()):

    // ── Secrets ─────────────────────────────────────────────────────────────
    DaprCapability.secrets(SecretStoreName("secretstore")):

      val apiKey = SecretsCapability.get(SecretKey("MY_API_KEY"))
      println(s"MY_API_KEY = ${apiKey.map(_.value).getOrElse("<not set>")}")

      val all = SecretsCapability.getBulk()
      println(s"all secrets: ${all.keys.map(_.value).toList.sorted.mkString(", ")}\n")

    // ── Configuration ────────────────────────────────────────────────────────
    DaprCapability.config(ConfigStoreName("configstore")):

      val keys  = Seq(ConfigKey("greeting"), ConfigKey("max-retries"))
      val items = ConfigurationCapability.get(keys)

      items.foreach: (k, item) =>
        println(s"config ${k.value} = ${item.value}  (version: ${item.version.value})")

      println("\nSubscribing to live config changes for 'greeting'...")
      println("(Use `dapr publish` or Redis CLI to update the key, then watch here.)\n")

      // The subscribe callback captures ConfigurationCapability via `?=>`.
      // The capture is tracked: ConfigurationCapability cannot be used after
      // the subscription is closed, and closing it before the block exits
      // would be a logic error caught by the scoping discipline.
      val sub = ConfigurationCapability.subscribe(Seq(ConfigKey("greeting")), Map.empty):
        update =>
          val changes = update.items.map((k, v) => s"${k.value}=${v.value}").mkString(", ")
          println(s"[config update] $changes")

      Thread.sleep(15_000)
      sub.close()
      println("Unsubscribed.")
