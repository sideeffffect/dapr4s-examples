package e2e

import java.util.concurrent.Semaphore

/** Base class for all E2E test suites.
  *
  * Each concrete suite declares its infrastructure fixture ([[OneShotInfra]] or [[ServerInfra]]) as a `val` and
  * registers it via `override def munitFixtures`. MUnit calls the fixture's beforeAll / afterAll automatically.
  *
  * The global semaphore enforces sequential suite execution so that multiple Docker Compose stacks never spin up
  * simultaneously, which would exhaust host resources and cause random healthz timeouts.
  *
  * Prerequisites: Docker must be available on the host (docker compose v2). No dapr CLI or `dapr init` is required.
  */
abstract class E2ESuite extends munit.FunSuite:

  private var lockAcquired = false

  override def beforeAll(): Unit =
    E2ESuite.lock.acquire() // before super so that fixture.beforeAll() runs inside the lock
    lockAcquired = true
    super.beforeAll()

  override def afterAll(): Unit =
    try super.afterAll()
    finally if lockAcquired then E2ESuite.lock.release()

object E2ESuite:
  private val lock = Semaphore(1)
