package e2e

class SecretsConfigTest extends E2ESuite:

  override val munitTimeout = scala.concurrent.duration.Duration(30, "s")

  var infra: OneShotInfra = null

  override def beforeAll(): Unit =
    super.beforeAll()
    infra = OneShotInfra.start("e2e-secrets", sidecarEnv = Map("MY_API_KEY" -> "e2e-test-secret"))

  override def afterAll(): Unit =
    if infra != null then infra.stop()
    super.afterAll()

  test("reads secret from environment") {
    val out = infra.run(
      jarModule = "secrets-config",
      mainClass = "secretsconfig.run",
      timeoutMs = 30_000,
    )
    assert(out.contains("MY_API_KEY   = e2e-test-secret"), clue(out))
    assert(out.contains("all secrets:"),                   clue(out))
  }

  test("reads config items from Redis") {
    infra.redisExec("SET", "greeting",    "Hello from E2E!")
    infra.redisExec("SET", "max-retries", "7")

    val out = infra.run(
      jarModule = "secrets-config",
      mainClass = "secretsconfig.run",
      timeoutMs = 30_000,
    )
    assert(out.contains("config greeting"),    clue(out))
    assert(out.contains("config max-retries"), clue(out))
  }
