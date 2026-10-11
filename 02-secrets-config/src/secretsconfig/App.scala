package secretsconfig

import dapr4s.*
import dapr4s.configuration.*
import dapr4s.secrets.*

// ── Capture-checked pure module ───────────────────────────────────────────────
// SecretsCapability.Instance and ConfigurationCapability.Instance are opened by
// the DaprCapability.secrets/configuration transformers, bound to one store each.
// The config subscription demo is inherently impure (callback + sleep) and
// lives entirely in the shell.
// ─────────────────────────────────────────────────────────────────────────────

def readSecrets()(using DaprCapability): (Option[SecretValue], Seq[String]) =
  DaprCapability.secrets(SecretStoreName("secretstore")):
    val apiKey = SecretsCapability.Instance.get(SecretKey("MY_API_KEY"))
    val allKeys = SecretsCapability.Instance.getBulk().keys.map(_.value).toSeq.sorted
    (apiKey, allKeys)

val configKeys: Seq[ConfigurationKey] = Seq(ConfigurationKey("greeting"), ConfigurationKey("max-retries"))

def readConfig()(using DaprCapability): Map[ConfigurationKey, ConfigurationItem] =
  DaprCapability.configuration(ConfigurationStoreName("configstore")):
    ConfigurationCapability.Instance.get(configKeys)
