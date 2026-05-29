package e2e

/** Path helpers shared across E2E tests. */
object Harness:

  // Mill sets this via forkArgs; fall back to cwd so IntelliJ works without extra config.
  val ProjectRoot: os.Path =
    Option(System.getProperty("e2e.projectRoot")).map(os.Path(_)).getOrElse(os.pwd)

  def jarFor(module: String): os.Path =
    Option(System.getProperty(s"e2e.jar.$module"))
      .map(os.Path(_))
      .getOrElse(locateJar(module))

  // Scans out/ for a pre-built assembly when the system property isn't set (e.g. IntelliJ).
  // Mill names the output directory after the module: out/NN-<module>-shell/assembly.dest/out.jar
  private def locateJar(module: String): os.Path =
    val outDir = ProjectRoot / "out"
    if !os.exists(outDir) then
      throw RuntimeException(
        s"No prebuilt jar for '$module' and out/ not found. Run: ./mill e2e.testForked")
    os.list(outDir)
      .filter(_.last.endsWith(s"-$module-shell"))
      .map(_ / "assembly.dest" / "out.jar")
      .find(os.exists)
      .getOrElse(throw RuntimeException(
        s"No prebuilt jar for '$module'. Run: ./mill e2e.testForked (or pre-assemble with ./mill 'NN-$module-shell.assembly')"))
