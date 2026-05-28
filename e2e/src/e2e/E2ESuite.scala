package e2e

import java.util.concurrent.Semaphore

/**
 * Base class for all E2E test suites.
 *
 * Each concrete suite is responsible for starting and stopping its own
 * [[OneShotInfra]] or [[ServerInfra]] in beforeAll / afterAll.  No shared
 * global state — every suite gets its own isolated Docker Compose stack.
 *
 * Prerequisites: Docker must be available on the host (docker compose v2).
 * No dapr CLI or `dapr init` is required.
 *
 * The global semaphore enforces sequential suite execution so that 7
 * Docker Compose stacks never spin up simultaneously, which would exhaust
 * host resources and cause random healthz timeouts.
 */
abstract class E2ESuite extends munit.FunSuite:
  override def beforeAll(): Unit =
    super.beforeAll()
    E2ESuite.lock.acquire()

  override def afterAll(): Unit =
    try super.afterAll()
    finally E2ESuite.lock.release()

object E2ESuite:
  private val lock = Semaphore(1)
