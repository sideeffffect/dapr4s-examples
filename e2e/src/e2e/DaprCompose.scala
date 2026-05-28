package e2e

import org.testcontainers.containers.ComposeContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.io.File
import java.time.Duration

private def composeFile(name: String): File =
  File(s"${System.getProperty("e2e.projectRoot")}/e2e/docker/$name")

// Generates e2e/docker/components/ from the canonical root components/,
// substituting localhost → redis so daprd reaches the Redis Docker service.
// e2e/docker/components/ is gitignored; root components/ is the source of truth.
private def prepareComponents(): Unit =
  val src  = os.Path(System.getProperty("e2e.projectRoot")) / "components"
  val dest = os.Path(System.getProperty("e2e.projectRoot")) / "e2e" / "docker" / "components"
  os.makeDir.all(dest)
  os.list(src).filter(_.ext == "yaml").foreach { f =>
    os.write.over(dest / f.last, os.read(f).replace("localhost:6379", "redis:6379"))
  }

// Matches "dapr initialized. Status: Running." in daprd's stdout.
private val DaprReadyPattern = ".*dapr initialized. Status: Running.*\\n"

/**
 * Dapr infrastructure for one-shot tests.
 *
 * Starts Redis, placement, scheduler, and a daprd sidecar (no app container).
 * The app process runs on the host via [[OneShotInfra.run]], with Dapr ports
 * injected as environment variables so the app reaches daprd via host-mapped ports.
 */
final class OneShotInfra private (private val compose: ComposeContainer):

  def daprHttpPort: Int = compose.getServicePort("dapr", 3500)
  def daprGrpcPort: Int = compose.getServicePort("dapr", 50001)

  def redisExec(args: String*): String =
    compose.getContainerByServiceName("redis").orElseThrow()
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

  def stop(): Unit = compose.stop()

object OneShotInfra:
  def start(appId: String, sidecarEnv: Map[String, String] = Map.empty): OneShotInfra =
    val c = ComposeContainer(composeFile("docker-compose.oneshot.yml"))
    c.withLocalCompose(true)
    prepareComponents()
    c.withEnv("APP_ID", appId)
    sidecarEnv.foreach((k, v) => c.withEnv(k, v))
    // Expose ports for getServicePort(); Wait.forListeningPort() is the default.
    c.withExposedService("dapr", 3500)
    c.withExposedService("dapr", 50001)
    // Log-based wait is instant (daprd logs this line ~60ms after it starts).
    c.waitingFor("dapr",
      Wait.forLogMessage(DaprReadyPattern, 1)
        .withStartupTimeout(Duration.ofMinutes(5)))
    c.start()
    new OneShotInfra(c)

/**
 * Dapr infrastructure for long-lived server tests.
 *
 * Starts Redis, placement, scheduler, the app JVM (in a container), and a daprd
 * sidecar that shares the app container's network namespace. Testcontainers maps
 * the app HTTP port (8080) and Dapr HTTP port (3500) to random host ports.
 */
final class ServerInfra private (private val compose: ComposeContainer):

  def daprHttpPort: Int = compose.getServicePort("app", 3500)
  def appHttpPort:  Int = compose.getServicePort("app", 8080)

  def redisExec(args: String*): String =
    compose.getContainerByServiceName("redis").orElseThrow()
      .execInContainer(("redis-cli" +: args.toSeq)*)
      .getStdout.nn.trim

  def stop(): Unit = compose.stop()

object ServerInfra:
  def start(appId: String, jarModule: String, mainClass: String): ServerInfra =
    val c = ComposeContainer(composeFile("docker-compose.server.yml"))
    c.withLocalCompose(true)
    prepareComponents()
    c.withEnv("APP_ID",     appId)
    c.withEnv("JAR_PATH",   Harness.jarFor(jarModule).toString)
    c.withEnv("MAIN_CLASS", mainClass)
    // Expose ports for getServicePort(); Wait.forListeningPort() is the default.
    // Port 3500 is on the `app` container because daprd shares its network namespace.
    c.withExposedService("app", 3500)
    c.withExposedService("app", 8080)
    // Log-based wait: daprd logs "dapr initialized" the moment it's ready.
    // This avoids HTTP polling and works regardless of Docker daemon load.
    c.waitingFor("dapr",
      Wait.forLogMessage(DaprReadyPattern, 1)
        .withStartupTimeout(Duration.ofMinutes(5)))
    c.start()
    new ServerInfra(c)
