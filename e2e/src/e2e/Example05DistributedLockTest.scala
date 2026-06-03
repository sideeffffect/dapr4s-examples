package e2e

class Example05DistributedLockTest extends E2ESuite:

  val infra = OneShotInfra("e2e-dist-lock")
  override def munitFixtures = List(infra)

  test("5 sequential workers all increment the counter") {
    val out = infra.run(jarModule = "distributed-lock", mainClass = "distributedlock.run")
    assert(out.contains("Counter after 5 sequential workers: 5"), clue(out))
    assert(out.contains("✓"), clue(out))
  }

  test("double-acquire: second lock denied, then granted after release") {
    val out = infra.run(jarModule = "distributed-lock", mainClass = "distributedlock.run")
    assert(out.contains("process-B tryLock while A holds it: false"), clue(out))
    assert(out.contains("process-B tryLock after A releases: true"), clue(out))
  }
