package e2e

/** Path helpers shared across E2E tests. */
object Harness:
  val ProjectRoot: os.Path = os.Path(System.getProperty("e2e.projectRoot"))

  def jarFor(module: String): os.Path =
    os.Path(System.getProperty(s"e2e.jar.$module"))
