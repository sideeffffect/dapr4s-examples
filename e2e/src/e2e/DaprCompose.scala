package e2e

import munit.Fixture
import org.testcontainers.containers.ComposeContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.time.Duration

private def composeFile(name: String) =
  (Harness.ProjectRoot / "e2e" / "docker" / name).toIO

// Copies root components/ into a temp dir, substituting localhost → redis
// so daprd in Docker reaches the Redis container by its service name.
// Returns the temp dir path for use as COMPONENTS_PATH in the compose env.
private def prepareComponents(): os.Path =
  val src = Harness.ProjectRoot / "components"
  val dest = os.temp.dir(prefix = "dapr-e2e-components")
  // Docker's daprd runs as a different uid; world-read+execute is required.
  os.perms.set(dest, "rwxr-xr-x")
  os.list(src).filter(_.ext == "yaml").foreach { f =>
    os.write(dest / f.last, os.read(f).replace("localhost:6379", "redis:6379"))
  }
  dest

// Matches "dapr initialized. Status: Running." in daprd's stdout.
private val DaprReadyPattern = ".*dapr initialized. Status: Running.*\\n"

/** Dapr infrastructure for one-shot tests.
  *
  * Starts Redis, placement, scheduler, and a daprd sidecar (no app container). The app process runs on the host via
  * [[run]], with Dapr ports injected as environment variables so the app reaches daprd via host-mapped ports.
  *
  * Extends [[Fixture]][Nothing] so MUnit manages start/stop via beforeAll/afterAll. apply() is intentionally
  * unimplemented — this fixture is used for its lifecycle side effects only; callers access ports and helpers directly
  * on this instance.
  */
final class OneShotInfra(
    appId: String,
    sidecarEnv: Map[String, String] = Map.empty,
    composeFileName: String = "docker-compose.oneshot.yml",
    // Extra component YAMLs (filename -> content) dropped into the prepared
    // components dir for this fixture only — used by examples whose component
    // can't live in the shared components/ dir (e.g. crypto, which needs /keys).
    extraComponents: Map[String, String] = Map.empty,
    // Extra compose env vars, evaluated in beforeAll so the test can generate
    // resources lazily (e.g. a temp keys dir for CRYPTO_KEYS_PATH).
    extraEnv: () => Map[String, String] = () => Map.empty,
) extends Fixture[Nothing](appId):

  private var compose: Option[ComposeContainer] = None

  def apply(): Nothing = throw UnsupportedOperationException(s"$fixtureName is a lifecycle fixture")

  def daprHttpPort: Int = compose.get.getServicePort("dapr", 3500)
  def daprGrpcPort: Int = compose.get.getServicePort("dapr", 50001)

  def redisExec(args: String*): String =
    compose.get
      .getContainerByServiceName("redis")
      .orElseThrow()
      .execInContainer(("redis-cli" +: args.toSeq)*)
      .getStdout
      .nn
      .trim

  def run(
      jarModule: String,
      mainClass: String,
      envVars: Map[String, String] = Map.empty,
      timeoutMs: Long = 120_000,
  ): String =
    os.proc("java", "-cp", Harness.jarFor(jarModule).toString, mainClass)
      .call(
        cwd = Harness.ProjectRoot,
        env = envVars ++ Map(
          "DAPR_HTTP_PORT" -> daprHttpPort.toString,
          "DAPR_GRPC_PORT" -> daprGrpcPort.toString,
        ),
        timeout = timeoutMs,
      )
      .out
      .text()

  override def beforeAll(): Unit =
    val c = ComposeContainer(composeFile(composeFileName))
    c.withLocalCompose(true)
    c.withEnv("APP_ID", appId)
    val compDir = prepareComponents()
    extraComponents.foreach((name, content) => os.write(compDir / name, content))
    c.withEnv("COMPONENTS_PATH", compDir.toString)
    sidecarEnv.foreach((k, v) => c.withEnv(k, v))
    extraEnv().foreach((k, v) => c.withEnv(k, v))
    c.withExposedService("dapr", 3500)
    c.withExposedService("dapr", 50001)
    c.waitingFor(
      "dapr",
      Wait
        .forLogMessage(DaprReadyPattern, 1)
        .withStartupTimeout(Duration.ofMinutes(5)),
    )
    c.start()
    compose = Some(c)

  override def afterAll(): Unit =
    compose.foreach(_.stop())

/** Dapr infrastructure for long-lived server tests.
  *
  * Starts Redis, placement, scheduler, the app JVM (in a container), and a daprd sidecar that shares the app
  * container's network namespace. Testcontainers maps the app HTTP port (8080) and Dapr HTTP port (3500) to random host
  * ports.
  *
  * Extends [[Fixture]][Nothing] so MUnit manages start/stop via beforeAll/afterAll. apply() is intentionally
  * unimplemented — this fixture is used for its lifecycle side effects only; callers access ports and helpers directly
  * on this instance.
  */
final class ServerInfra(
    appId: String,
    jarModule: String,
    mainClass: String,
    postStart: ServerInfra => Unit = _ => (),
    // Override to boot a compose file other than the shared server template
    // (e.g. one that adds a Kafka broker for the bindings example).
    composeFileName: String = "docker-compose.server.yml",
    // Extra component YAMLs (filename -> content) dropped into the prepared
    // components dir for this fixture only — used by examples whose component
    // can't live in the shared components/ dir (e.g. the binding components,
    // which would make every other example's daprd dial Kafka).
    extraComponents: Map[String, String] = Map.empty,
    // Extra compose env vars, evaluated in beforeAll (e.g. an absolute path to
    // WireMock stub mappings for the bindings example's HTTP binding).
    extraEnv: () => Map[String, String] = () => Map.empty,
) extends Fixture[Nothing](appId):

  private var compose: Option[ComposeContainer] = None

  def apply(): Nothing = throw UnsupportedOperationException(s"$fixtureName is a lifecycle fixture")

  def daprHttpPort: Int = compose.get.getServicePort("app", 3500)
  def appHttpPort: Int = compose.get.getServicePort("app", 8080)

  def redisExec(args: String*): String =
    compose.get
      .getContainerByServiceName("redis")
      .orElseThrow()
      .execInContainer(("redis-cli" +: args.toSeq)*)
      .getStdout
      .nn
      .trim

  override def beforeAll(): Unit =
    val c = ComposeContainer(composeFile(composeFileName))
    c.withLocalCompose(true)
    c.withEnv("APP_ID", appId)
    c.withEnv("JAR_PATH", Harness.jarFor(jarModule).toString)
    c.withEnv("MAIN_CLASS", mainClass)
    val compDir = prepareComponents()
    extraComponents.foreach((name, content) => os.write(compDir / name, content))
    c.withEnv("COMPONENTS_PATH", compDir.toString)
    extraEnv().foreach((k, v) => c.withEnv(k, v))
    c.withExposedService("app", 3500)
    c.withExposedService("app", 8080)
    c.waitingFor(
      "dapr",
      Wait
        .forLogMessage(DaprReadyPattern, 1)
        .withStartupTimeout(Duration.ofMinutes(5)),
    )
    c.start()
    compose = Some(c)
    postStart(this)

  override def afterAll(): Unit =
    compose.foreach(_.stop())

/** Infrastructure for the 14 observability example.
  *
  * Boots the orders + pricing apps (each with a daprd sidecar configured to export OTLP traces), an OpenTelemetry
  * Collector that funnels traces/metrics/logs into a self-hosted SigNoz stack, and the Diagrid dashboard. Heavy: ~13
  * containers incl. ClickHouse (needs >=4GB Docker memory); the global suite semaphore keeps it from overlapping other
  * suites.
  *
  * When [[presentation]] is true the fixture pins fixed, memorable host ports (so the dashboard URLs are stable across
  * runs) instead of letting Docker assign ephemeral ones.
  */
final class ObservabilityInfra(presentation: Boolean) extends Fixture[Nothing]("14-observability"):

  private var compose: Option[ComposeContainer] = None

  def apply(): Nothing = throw UnsupportedOperationException(s"$fixtureName is a lifecycle fixture")

  // daprd shares the orders/pricing app netns, so its ports map on the app service.
  def ordersDaprPort: Int = compose.get.getServicePort("orders", 3500)
  def ordersMetricsPort: Int = compose.get.getServicePort("orders", 9090)
  def signozPort: Int = compose.get.getServicePort("signoz", 8080)
  def diagridPort: Int = compose.get.getServicePort("diagrid-dashboard", 8080)
  def collectorMetricsPort: Int = compose.get.getServicePort("otel-collector", 8888)

  override def beforeAll(): Unit =
    val c = ComposeContainer(composeFile("docker-compose.14-observability.yml"))
    c.withLocalCompose(true)
    c.withEnv("ORDERS_JAR", Harness.jarFor("orders").toString)
    c.withEnv("PRICING_JAR", Harness.jarFor("pricing").toString)
    c.withEnv("COMPONENTS_PATH", prepareComponents().toString)
    if presentation then
      // Fixed, memorable host ports for the live demo (collision risk is accepted).
      c.withEnv("SIGNOZ_UI_HOST", "3301")
      c.withEnv("DIAGRID_HOST", "8080")
      c.withEnv("ORDERS_DAPR_HOST", "3500")
      c.withEnv("ORDERS_METRICS_HOST", "9090")
      c.withEnv("COLLECTOR_HOST", "8888")
    val long = Duration.ofMinutes(8)
    // `orders` is a normal JVM image (has a shell), so the default port-listening wait
    // is fine. The other three are distroless (no /bin/sh): the default wait's internal
    // probe execs a shell inside the container and fails noisily — so wait on an
    // external HTTP endpoint instead, which never execs into the container.
    val httpUp: java.util.function.Predicate[Integer] = (code: Integer) => code.intValue() < 500
    c.withExposedService("orders", 3500)
    c.withExposedService("orders", 9090)
    c.withExposedService(
      "signoz",
      8080,
      Wait.forHttp("/api/v1/health").forPort(8080).forStatusCodeMatching(httpUp).withStartupTimeout(long),
    )
    c.withExposedService(
      "diagrid-dashboard",
      8080,
      Wait.forHttp("/").forPort(8080).forStatusCodeMatching(httpUp).withStartupTimeout(long),
    )
    c.withExposedService(
      "otel-collector",
      8888,
      Wait.forHttp("/metrics").forPort(8888).forStatusCodeMatching(httpUp).withStartupTimeout(long),
    )
    c.waitingFor("orders-dapr", Wait.forLogMessage(DaprReadyPattern, 1).withStartupTimeout(long))
    c.waitingFor("pricing-dapr", Wait.forLogMessage(DaprReadyPattern, 1).withStartupTimeout(long))
    c.waitingFor("signoz", Wait.forHealthcheck().withStartupTimeout(long))
    // Critical: the SigNoz collector blocks in `migrate sync check` until ClickHouse
    // migrations finish and only then opens its OTLP :4317 listener. Gate on its
    // readiness so load is driven *after* SigNoz can ingest — otherwise the funnel's
    // exporter retries time out and the dashboards stay empty.
    c.waitingFor("signoz-otel-collector", Wait.forLogMessage(".*Everything is ready.*\n", 1).withStartupTimeout(long))
    c.start()
    compose = Some(c)

  override def afterAll(): Unit =
    compose.foreach(_.stop())

/** Dapr infrastructure for multi-service E2E tests (examples 08 and 09).
  *
  * Unlike [[ServerInfra]] (one app + one sidecar), this boots a whole compose stack of several app containers, each
  * paired with its own daprd sidecar sharing the app's network namespace, plus shared redis/placement/scheduler. The
  * sidecars share one pub/sub + state backend (Redis) and resolve each other for service invocation via mDNS on the
  * shared bridge network.
  *
  * @param composeFileName
  *   the compose file under e2e/docker/ to boot.
  * @param jars
  *   compose env-var name -> jar module name (resolved via [[Harness.jarFor]]); bound into the app containers.
  * @param daprServices
  *   the daprd compose service names to wait on (each must log "dapr initialized. Status: Running.").
  * @param appServices
  *   compose service names whose app + dapr ports should be exposed to the host.
  */
final class MultiServerInfra(
    composeFileName: String,
    jars: Map[String, String],
    daprServices: List[String],
    appServices: List[String],
) extends Fixture[Nothing](composeFileName):

  private var compose: Option[ComposeContainer] = None

  def apply(): Nothing = throw UnsupportedOperationException(s"$fixtureName is a lifecycle fixture")

  /** Host-mapped app HTTP port (container 8080) for the given compose service. */
  def appPort(service: String): Int = compose.get.getServicePort(service, 8080)

  /** Host-mapped Dapr HTTP port (container 3500) for the given compose service. */
  def daprPort(service: String): Int = compose.get.getServicePort(service, 3500)

  def redisExec(args: String*): String =
    compose.get
      .getContainerByServiceName("redis")
      .orElseThrow()
      .execInContainer(("redis-cli" +: args.toSeq)*)
      .getStdout
      .nn
      .trim

  override def beforeAll(): Unit =
    val c = ComposeContainer(composeFile(composeFileName))
    c.withLocalCompose(true)
    c.withEnv("COMPONENTS_PATH", prepareComponents().toString)
    jars.foreach((envVar, module) => c.withEnv(envVar, Harness.jarFor(module).toString))
    appServices.foreach { svc =>
      c.withExposedService(svc, 8080)
      c.withExposedService(svc, 3500)
    }
    daprServices.foreach { svc =>
      c.waitingFor(
        svc,
        Wait.forLogMessage(DaprReadyPattern, 1).withStartupTimeout(Duration.ofMinutes(5)),
      )
    }
    c.start()
    compose = Some(c)

  override def afterAll(): Unit =
    compose.foreach(_.stop())
