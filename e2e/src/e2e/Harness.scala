package e2e

/** Path helpers shared across E2E tests. */
object Harness:

  // Mill sets this via forkArgs; fall back to cwd so IntelliJ works without extra config.
  val ProjectRoot: os.Path =
    Option(System.getProperty("e2e.projectRoot")).map(os.Path(_)).getOrElse(os.pwd)

  // An optional override (a `-De2e.jar.<module>=…` system property) wins; otherwise the
  // jar is located in out/. forkArgs no longer sets these properties (so that
  // e2e.testForked does not force-build every example), so locateJar is the normal path.
  def jarFor(module: String): os.Path =
    Option(System.getProperty(s"e2e.jar.$module"))
      .map(os.Path(_))
      .getOrElse(locateJar(module))

  // Scans out/ for a pre-built assembly. Build the JARs first, e.g. `./mill __.assembly`
  // (or just the ones a run needs: `./mill 14-orders-shell.assembly 14-pricing-shell.assembly`).
  // Mill names the output directory after the module: out/NN-<module>-shell/assembly.dest/out.jar
  private def locateJar(module: String): os.Path =
    val outDir = ProjectRoot / "out"
    if !os.exists(outDir) then
      throw RuntimeException(s"No prebuilt jar for '$module' and out/ not found. Build first: ./mill __.assembly")
    os.list(outDir)
      .filter(_.last.endsWith(s"-$module-shell"))
      .map(_ / "assembly.dest" / "out.jar")
      .find(os.exists)
      .getOrElse(
        throw RuntimeException(
          s"No prebuilt jar for '$module'. Build first: ./mill __.assembly " +
            s"(or just this one: ./mill 'NN-$module-shell.assembly')",
        ),
      )

  // Scala.js (15/16/17) twin of jarFor: the linked Wasm bundle dir of a `*-shell` JS module,
  // i.e. out/<shellModule>/fastLinkJS.dest (holding main.js + main.wasm + __loader.js). Build it
  // first, e.g. `./mill 15-scan-gateway-shell.fastLinkJS`. `shellModule` is the FULL module name
  // (e.g. "15-scan-gateway-shell"). An optional `-De2e.bundle.<shellModule>=…` override wins.
  def jsBundleFor(shellModule: String): os.Path =
    Option(System.getProperty(s"e2e.bundle.$shellModule"))
      .map(os.Path(_))
      .getOrElse {
        val dest = ProjectRoot / "out" / shellModule / "fastLinkJS.dest"
        if os.exists(dest / "main.js") then dest
        else
          throw RuntimeException(
            s"No linked Scala.js bundle for '$shellModule' at $dest. " +
              s"Build first: ./mill '$shellModule.fastLinkJS'",
          )
      }
