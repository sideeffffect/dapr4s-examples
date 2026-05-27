package e2e

/**
 * Base class for all E2E test suites.
 *
 * - Verifies Dapr's Redis is reachable (dapr init must have been run).
 * - Flushes all Redis data in beforeAll so each suite starts with a clean
 *   state store, pub/sub queues, and actor/workflow history.
 *
 * Suites may run in parallel (Mill workers), so flushing only in beforeAll
 * — not beforeEach — prevents one suite's flush from corrupting another
 * suite's in-flight test.  Tests within a suite that need per-test isolation
 * must manage their own state (e.g. unique actor IDs, explicit flushes).
 *
 * Prerequisites (assumed present on the test machine):
 *   - `docker` CLI accessible on PATH
 *   - `dapr` CLI installed and `dapr init` already run (provides the
 *     placement service, scheduler, and Dapr runtime binaries)
 */
abstract class E2ESuite extends munit.FunSuite:

  override def beforeAll(): Unit =
    E2EInfra.ensureStarted()
    E2EInfra.flushRedis()
    super.beforeAll()
