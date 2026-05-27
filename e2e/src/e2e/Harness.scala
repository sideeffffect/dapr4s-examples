package e2e

/** Process-management helpers for E2E tests. */
object Harness:
  val ProjectRoot: os.Path = os.Path(System.getProperty("e2e.projectRoot"))

  def jarFor(module: String): os.Path =
    os.Path(System.getProperty(s"e2e.jar.$module"))

  /**
   * Run a one-shot app via `dapr run -- java -cp <jar> <mainClass>` and return
   * its combined stdout. Blocks until the app exits.
   */
  def runOneShot(
    appId:     String,
    jarModule: String,
    mainClass: String,
    daprPort:  Int,
    envVars:   Map[String, String] = Map.empty,
    timeoutMs: Long = 120_000,
  ): String =
    os.proc(
      "dapr", "run",
      "--app-id",          appId,
      "--dapr-http-port",  daprPort.toString,
      "--resources-path", E2EInfra.componentsDir.toString,
      "--log-level",       "error",
      "--",
      "java", "-cp", jarFor(jarModule).toString, mainClass,
    ).call(
      cwd     = ProjectRoot,
      env     = envVars,
      timeout = timeoutMs,
    ).out.text()

  /**
   * Start a long-lived server app in the background. Call waitForPort after
   * this to know when both the Dapr sidecar and the app are ready.
   */
  def spawnServer(
    appId:     String,
    jarModule: String,
    mainClass: String,
    appPort:   Int,
    daprPort:  Int,
    envVars:   Map[String, String] = Map.empty,
  ): os.SubProcess =
    os.proc(
      "dapr", "run",
      "--app-id",          appId,
      "--app-port",        appPort.toString,
      "--dapr-http-port",  daprPort.toString,
      "--resources-path", E2EInfra.componentsDir.toString,
      "--log-level",       "error",
      "--",
      "java", "-cp", jarFor(jarModule).toString, mainClass,
    ).spawn(cwd = ProjectRoot, env = envVars, stderr = os.Pipe)

  def stopApp(appId: String, proc: os.SubProcess): Unit =
    os.proc("dapr", "stop", "--app-id", appId).call(check = false)
    proc.destroy()
    proc.wrapped.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)

  /** Poll until TCP port accepts a connection or we time out. */
  def waitForPort(port: Int, timeoutMs: Long = 60_000): Unit =
    val deadline = System.currentTimeMillis() + timeoutMs
    var up = false
    while !up && System.currentTimeMillis() < deadline do
      try
        java.net.Socket("localhost", port).close()
        up = true
      catch case _: java.io.IOException =>
        Thread.sleep(300)
    if !up then
      throw RuntimeException(s"Timed out waiting for port $port after ${timeoutMs}ms")
