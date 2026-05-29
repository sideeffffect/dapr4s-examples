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
  val src  = Harness.ProjectRoot / "components"
  val dest = os.temp.dir(prefix = "dapr-e2e-components")
  // Docker's daprd runs as a different uid; world-read+execute is required.
  os.perms.set(dest, "rwxr-xr-x")
  os.list(src).filter(_.ext == "yaml").foreach { f =>
    os.write(dest / f.last, os.read(f).replace("localhost:6379", "redis:6379"))
  }
  dest

// Matches "dapr initialized. Status: Running." in daprd's stdout.
private val DaprReadyPattern = ".*dapr initialized. Status: Running.*\\n"

/**
 * Dapr infrastructure for one-shot tests.
 *
 * Starts Redis, placement, scheduler, and a daprd sidecar (no app container).
 * The app process runs on the host via [[run]], with Dapr ports injected as
 * environment variables so the app reaches daprd via host-mapped ports.
 *
 * Extends [[Fixture]][Nothing] so MUnit manages start/stop via beforeAll/afterAll.
 * apply() is intentionally unimplemented — this fixture is used for its lifecycle
 * side effects only; callers access ports and helpers directly on this instance.
 */
final class OneShotInfra(
  appId:      String,
  sidecarEnv: Map[String, String] = Map.empty,
) extends Fixture[Nothing](appId):

  private var compose: Option[ComposeContainer] = None

  def apply(): Nothing = throw UnsupportedOperationException(s"$fixtureName is a lifecycle fixture")

  def daprHttpPort: Int = compose.get.getServicePort("dapr", 3500)
  def daprGrpcPort: Int = compose.get.getServicePort("dapr", 50001)

  def redisExec(args: String*): String =
    compose.get.getContainerByServiceName("redis").orElseThrow()
      .execInContainer(("redis-cli" +: args.toSeq)*)
      .getStdout.nn.trim

  def run(
    jarModule: String,
    mainClass: String,
    envVars:   Map[String, String] = Map.empty,
    timeoutMs: Long = 120_000,
  ): String =
    os.proc("java", "-cp", Harness.jarFor(jarModule).toString, mainClass)
      .call(
        cwd     = Harness.ProjectRoot,
        env     = envVars ++ Map(
          "DAPR_HTTP_PORT" -> daprHttpPort.toString,
          "DAPR_GRPC_PORT" -> daprGrpcPort.toString,
        ),
        timeout = timeoutMs,
      ).out.text()

  override def beforeAll(): Unit =
    val c = ComposeContainer(composeFile("docker-compose.oneshot.yml"))
    c.withLocalCompose(true)
    c.withEnv("APP_ID",          appId)
    c.withEnv("COMPONENTS_PATH", prepareComponents().toString)
    sidecarEnv.foreach((k, v) => c.withEnv(k, v))
    c.withExposedService("dapr", 3500)
    c.withExposedService("dapr", 50001)
    c.waitingFor("dapr",
      Wait.forLogMessage(DaprReadyPattern, 1)
        .withStartupTimeout(Duration.ofMinutes(5)))
    c.start()
    compose = Some(c)

  override def afterAll(): Unit =
    compose.foreach(_.stop())

/**
 * Dapr infrastructure for long-lived server tests.
 *
 * Starts Redis, placement, scheduler, the app JVM (in a container), and a daprd
 * sidecar that shares the app container's network namespace. Testcontainers maps
 * the app HTTP port (8080) and Dapr HTTP port (3500) to random host ports.
 *
 * Extends [[Fixture]][Nothing] so MUnit manages start/stop via beforeAll/afterAll.
 * apply() is intentionally unimplemented — this fixture is used for its lifecycle
 * side effects only; callers access ports and helpers directly on this instance.
 */
final class ServerInfra(
  appId:     String,
  jarModule: String,
  mainClass: String,
  postStart: ServerInfra => Unit = _ => (),
) extends Fixture[Nothing](appId):

  private var compose: Option[ComposeContainer] = None

  def apply(): Nothing = throw UnsupportedOperationException(s"$fixtureName is a lifecycle fixture")

  def daprHttpPort: Int = compose.get.getServicePort("app", 3500)
  def appHttpPort:  Int = compose.get.getServicePort("app", 8080)

  def redisExec(args: String*): String =
    compose.get.getContainerByServiceName("redis").orElseThrow()
      .execInContainer(("redis-cli" +: args.toSeq)*)
      .getStdout.nn.trim

  override def beforeAll(): Unit =
    val c = ComposeContainer(composeFile("docker-compose.server.yml"))
    c.withLocalCompose(true)
    c.withEnv("APP_ID",          appId)
    c.withEnv("JAR_PATH",        Harness.jarFor(jarModule).toString)
    c.withEnv("MAIN_CLASS",      mainClass)
    c.withEnv("COMPONENTS_PATH", prepareComponents().toString)
    c.withExposedService("app", 3500)
    c.withExposedService("app", 8080)
    c.waitingFor("dapr",
      Wait.forLogMessage(DaprReadyPattern, 1)
        .withStartupTimeout(Duration.ofMinutes(5)))
    c.start()
    compose = Some(c)
    postStart(this)

  override def afterAll(): Unit =
    compose.foreach(_.stop())
