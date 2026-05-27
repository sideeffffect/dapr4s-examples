package e2e

class SecretsConfigTest extends E2ESuite:

  // The secrets-config shell has a 15 s config-subscription demo at the end;
  // allow up to 30 s total.
  override val munitTimeout = scala.concurrent.duration.Duration(30, "s")

  test("reads secret from environment") {
    val out = Harness.runOneShot(
      appId     = "e2e-secrets",
      jarModule = "secrets-config",
      mainClass = "secretsconfig.run",
      daprPort  = 3502,
      envVars   = Map("MY_API_KEY" -> "e2e-test-secret"),
      timeoutMs = 30_000,
    )
    assert(out.contains("MY_API_KEY   = e2e-test-secret"), clue(out))
    assert(out.contains("all secrets:"),                   clue(out))
  }

  test("reads config items from Redis") {
    // Pre-populate the Redis config store (plain string values).
    E2EInfra.redisCmd("SET", "greeting",    "Hello from E2E!")
    E2EInfra.redisCmd("SET", "max-retries", "7")

    val out = Harness.runOneShot(
      appId     = "e2e-config",
      jarModule = "secrets-config",
      mainClass = "secretsconfig.run",
      daprPort  = 3502,
      envVars   = Map("MY_API_KEY" -> "dummy"),
      timeoutMs = 30_000,
    )
    assert(out.contains("config greeting"),    clue(out))
    assert(out.contains("config max-retries"), clue(out))
  }
