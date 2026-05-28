package e2e

import java.util.concurrent.Semaphore
import munit.Fixture

/**
 * Base class for all E2E test suites.
 *
 * Each concrete suite declares its infrastructure fixture via [[oneShot]] or
 * [[server]] and registers it with `override def munitFixtures = List(infra)`.
 * MUnit calls the fixture's beforeAll / afterAll automatically; no manual
 * setup/teardown overrides are needed in concrete suites.
 *
 * The global semaphore enforces sequential suite execution so that multiple
 * Docker Compose stacks never spin up simultaneously, which would exhaust
 * host resources and cause random healthz timeouts.
 *
 * Prerequisites: Docker must be available on the host (docker compose v2).
 * No dapr CLI or `dapr init` is required.
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

  protected def oneShot(
    appId:      String,
    sidecarEnv: Map[String, String] = Map.empty,
  ): Fixture[OneShotInfra] =
    infraFixture(appId, OneShotInfra.start(appId, sidecarEnv), _.stop())

  protected def server(
    appId:     String,
    jarModule: String,
    mainClass: String,
    postStart: ServerInfra => Unit = _ => (),
  ): Fixture[ServerInfra] =
    infraFixture(
      name  = appId,
      start = { val i = ServerInfra.start(appId, jarModule, mainClass); postStart(i); i },
      stop  = _.stop(),
    )

  private def infraFixture[I](name: String, start: => I, stop: I => Unit): Fixture[I] =
    new Fixture[I](name):
      private var resource: Option[I] = None
      def apply(): I                  = resource.get
      override def beforeAll(): Unit  = resource = Some(start)
      override def afterAll(): Unit   = resource.foreach(stop)

object E2ESuite:
  private val lock = Semaphore(1)
