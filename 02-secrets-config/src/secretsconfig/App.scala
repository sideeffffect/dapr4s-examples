package secretsconfig

import dapr4s.*

// ── Capture-checked pure module ───────────────────────────────────────────────
// SecretsCapability and ConfigurationCapability are threaded as `using` params.
// The config subscription demo is inherently impure (callback + sleep) and
// lives entirely in the shell.
// ─────────────────────────────────────────────────────────────────────────────

def readSecrets()(using DaprCapability): (Option[SecretValue], Seq[String]) =
  DaprCapability.secrets(SecretStoreName("secretstore")):
    val apiKey = SecretsCapability.get(SecretKey("MY_API_KEY"))
    val allKeys = SecretsCapability.getBulk().keys.map(_.value).toSeq.sorted
    (apiKey, allKeys)

val configKeys: Seq[ConfigurationKey] = Seq(ConfigurationKey("greeting"), ConfigurationKey("max-retries"))

def readConfig()(using DaprCapability): Map[ConfigurationKey, ConfigurationItem] =
  DaprCapability.configuration(ConfigurationStoreName("configstore")):
    ConfigurationCapability.get(configKeys)
