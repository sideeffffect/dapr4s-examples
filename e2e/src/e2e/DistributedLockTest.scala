package e2e

class DistributedLockTest extends E2ESuite:

  test("5 sequential workers all increment the counter") {
    val out = Harness.runOneShot(
      appId     = "e2e-dist-lock",
      jarModule = "distributed-lock",
      mainClass = "distributedlock.run",
      daprPort  = 3505,
    )
    assert(out.contains("Counter after 5 sequential workers: 5"), clue(out))
    assert(out.contains("✓"), clue(out))
  }

  test("double-acquire: second lock denied, then granted after release") {
    val out = Harness.runOneShot(
      appId     = "e2e-dist-lock",
      jarModule = "distributed-lock",
      mainClass = "distributedlock.run",
      daprPort  = 3505,
    )
    assert(out.contains("process-B tryLock while A holds it: false"), clue(out))
    assert(out.contains("process-B tryLock after A releases: true"),  clue(out))
  }
