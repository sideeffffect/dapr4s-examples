package distributedlock

import dapr4s.*
import scala.concurrent.duration.*

// ── Safe Scala guarantee ────────────────────────────────────────────────────
// DistributedLockCapability and StateCapability extend ExclusiveCapability,
// which means the CC compiler prevents them from being captured in lambdas
// passed to other threads.  This is correct: distributed locks are designed
// for cross-process mutual exclusion, not within-JVM thread coordination
// (use Java synchronized / AtomicXxx for that).
//
// This demo runs sequentially within one process, simulating the acquire /
// use / release lifecycle that would span multiple microservice instances in
// production.  The state store is used as the shared counter to verify that
// the lock gives exclusive access.
// ─────────────────────────────────────────────────────────────────────────────

@main def run(): Unit =
  println("=== 05 distributed-lock ===\n")

  DaprRuntime.run(DaprRuntimeConfig()):
    DaprCapability.state(StoreName("statestore")):
      DaprCapability.lock(StoreName("lockstore")):

        val resource = LockResourceId("my-resource")
        val counter  = StateKey("lock-counter")

        StateCapability.save(counter, 0)

        // ── Acquire, increment, release, repeat ─────────────────────────────
        // In production each of the N iterations would be a separate process.
        val N = 5
        for i <- 1 to N do
          val owner = LockOwner(s"worker-$i")

          if DistributedLockCapability.tryLock(resource, owner, expirySeconds = 10) then
            try
              val v = StateCapability.get[Int](counter).getOrElse(0)
              println(s"[$i] acquired lock; counter = $v → ${v + 1}")
              StateCapability.save(counter, v + 1)
            finally
              DistributedLockCapability.unlock(resource, owner)
          else
            println(s"[$i] could not acquire lock (unexpected in sequential demo)")

        val result = StateCapability.get[Int](counter).getOrElse(-1)
        println(s"\nExpected $N, got $result  ${if result == N then "✓" else "✗"}")

        // ── Demonstrate that a lock cannot be double-acquired ─────────────
        println("\n--- Double-acquire attempt ---")
        val ownerA = LockOwner("process-A")
        val ownerB = LockOwner("process-B")
        if DistributedLockCapability.tryLock(resource, ownerA, expirySeconds = 10) then
          println("process-A acquired lock")
          val secondAcquire = DistributedLockCapability.tryLock(resource, ownerB, expirySeconds = 1)
          println(s"process-B tryLock while A holds it: $secondAcquire (expected false)")
          DistributedLockCapability.unlock(resource, ownerA)
          println("process-A released lock")
          val afterRelease = DistributedLockCapability.tryLock(resource, ownerB, expirySeconds = 10)
          println(s"process-B tryLock after A releases: $afterRelease (expected true)")
          if afterRelease then
            DistributedLockCapability.unlock(resource, ownerB)

  println("\n[distributed-lock] done.")
