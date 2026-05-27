package e2e

/**
 * Shared E2E infrastructure.
 *
 * `dapr init` (self-hosted) provides Redis at localhost:6379 and runs the
 * placement/scheduler services as Docker containers. We write our own
 * component YAMLs that point at that Redis so we control the full set
 * (statestore, pubsub, lockstore, configstore, secretstore).
 *
 * No Testcontainers needed — avoids Docker API version compatibility issues
 * and works identically on CI after `dapr init`.
 */
object E2EInfra:

  private val lock          = Object()
  private var running       = false
  private var _componentsDir: os.Path = null

  def ensureStarted(): Unit = lock.synchronized:
    if !running then
      _componentsDir = writeComponents()
      // Sanity-check that Dapr's Redis is reachable
      val ping = os.proc("docker", "exec", "dapr_redis", "redis-cli", "PING")
        .call(check = false).out.text().trim
      if ping != "PONG" then
        throw RuntimeException(
          "Dapr Redis is not reachable. Run `dapr init` before running E2E tests."
        )
      running = true

  def componentsDir: os.Path = _componentsDir

  def flushRedis(): Unit =
    os.proc("docker", "exec", "dapr_redis", "redis-cli", "FLUSHALL").call()

  def redisCmd(args: String*): String =
    os.proc(Seq("docker", "exec", "dapr_redis", "redis-cli") ++ args)
      .call().out.text().trim

  // ── Component YAML generation ────────────────────────────────────────────────

  private def writeComponents(): os.Path =
    val dir = os.temp.dir(prefix = "dapr-e2e-")
    os.write(dir / "statestore.yaml",  statestoreYaml)
    os.write(dir / "pubsub.yaml",      pubsubYaml)
    os.write(dir / "lockstore.yaml",   lockstoreYaml)
    os.write(dir / "configstore.yaml", configstoreYaml)
    os.write(dir / "secretstore.yaml", secretstoreYaml)
    dir

  private val statestoreYaml = """|apiVersion: dapr.io/v1alpha1
    |kind: Component
    |metadata:
    |  name: statestore
    |spec:
    |  type: state.redis
    |  version: v1
    |  metadata:
    |  - name: redisHost
    |    value: localhost:6379
    |  - name: redisPassword
    |    value: ""
    |  - name: actorStateStore
    |    value: "true"
    |""".stripMargin

  private val pubsubYaml = """|apiVersion: dapr.io/v1alpha1
    |kind: Component
    |metadata:
    |  name: pubsub
    |spec:
    |  type: pubsub.redis
    |  version: v1
    |  metadata:
    |  - name: redisHost
    |    value: localhost:6379
    |  - name: redisPassword
    |    value: ""
    |""".stripMargin

  private val lockstoreYaml = """|apiVersion: dapr.io/v1alpha1
    |kind: Component
    |metadata:
    |  name: lockstore
    |spec:
    |  type: lock.redis
    |  version: v1
    |  metadata:
    |  - name: redisHost
    |    value: localhost:6379
    |  - name: redisPassword
    |    value: ""
    |""".stripMargin

  private val configstoreYaml = """|apiVersion: dapr.io/v1alpha1
    |kind: Component
    |metadata:
    |  name: configstore
    |spec:
    |  type: configuration.redis
    |  version: v1
    |  metadata:
    |  - name: redisHost
    |    value: localhost:6379
    |  - name: redisPassword
    |    value: ""
    |""".stripMargin

  private val secretstoreYaml = """|apiVersion: dapr.io/v1alpha1
    |kind: Component
    |metadata:
    |  name: secretstore
    |spec:
    |  type: secretstores.local.env
    |  version: v1
    |  metadata:
    |  - name: prefix
    |    value: ""
    |""".stripMargin
