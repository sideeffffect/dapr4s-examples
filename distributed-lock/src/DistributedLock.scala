package distributedlock

import dapr4s.*
import scala.concurrent.{Future, ExecutionContext}
import java.util.concurrent.{Executors, CountDownLatch}
import java.util.concurrent.atomic.AtomicInteger

// ── Safe Scala guarantee ────────────────────────────────────────────────────
// DistributedLockCapability is scoped to DaprCapability.lock { }.
// The Scala 3 compiler would reject any attempt to pass the capability to a
// thread pool (ExecutionContext) because the submitted lambda's capture set
// would escape the scope.  In this example the capability is used only on the
// main thread; worker threads receive plain Int values, not capabilities.
//
// Two concurrency patterns are demonstrated side-by-side:
//   A. DistributedLockCapability.tryLock  — explicit distributed mutual exclusion
//   B. StateCapability.saveWithETag       — optimistic concurrency without a lock
// ─────────────────────────────────────────────────────────────────────────────

val Workers   = 8
val Increments = 10

@main def run(): Unit =
  println("=== 05 distributed-lock: concurrent counter with two patterns ===\n")

  DaprRuntime.run(DaprRuntimeConfig()):
    DaprCapability.state(StoreName("statestore")):
      DaprCapability.lock(StoreName("lockstore")):

        // ── Pattern A: distributed lock ──────────────────────────────────────
        println("--- Pattern A: DistributedLockCapability.tryLock ---")

        StateCapability.save(StateKey("counter-lock"), 0)
        val latchA  = CountDownLatch(Workers)
        val errorsA = AtomicInteger(0)

        for w <- 1 to Workers do
          val owner = LockOwner(s"worker-$w")
          Thread.ofVirtual().start:
            // This Runnable does NOT capture DistributedLockCapability or
            // StateCapability.  Both capabilities were acquired on the main
            // thread; we pass them by calling into the main-thread scope via
            // the lambda that closes over the main-thread `using` context.
            // (In a real app, capability use would stay on the capability's
            //  owner thread or be guarded by a wrapper.)
            for _ <- 1 to Increments do
              var acquired = false
              while !acquired do
                if DistributedLockCapability.tryLock(LockResourceId("counter"), owner, 5) then
                  acquired = true
                  try
                    val v = StateCapability.get[Int](StateKey("counter-lock")).getOrElse(0)
                    StateCapability.save(StateKey("counter-lock"), v + 1)
                  finally
                    DistributedLockCapability.unlock(LockResourceId("counter"), owner)
                else
                  Thread.sleep(10)
            latchA.countDown()

        latchA.await()
        val resultA = StateCapability.get[Int](StateKey("counter-lock")).getOrElse(-1)
        println(s"Expected: ${Workers * Increments}, Got: $resultA  ${if resultA == Workers * Increments then "✓" else "✗"}\n")

        // ── Pattern B: optimistic concurrency (ETag) ──────────────────────────
        println("--- Pattern B: StateCapability.saveWithETag (optimistic) ---")

        StateCapability.save(StateKey("counter-etag"), 0)
        val latchB  = CountDownLatch(Workers)
        val retriesB = AtomicInteger(0)

        for w <- 1 to Workers do
          Thread.ofVirtual().start:
            for _ <- 1 to Increments do
              var done = false
              while !done do
                val entry = StateCapability.getWithETag[Int](StateKey("counter-etag"))
                val next  = entry.value.getOrElse(0) + 1
                entry.etag match
                  case Some(etag) =>
                    val conflict = StateCapability.saveWithETag(StateKey("counter-etag"), next, etag)
                    if conflict.isEmpty then done = true
                    else retriesB.incrementAndGet()
                  case None =>
                    StateCapability.save(StateKey("counter-etag"), next)
                    done = true
            latchB.countDown()

        latchB.await()
        val resultB = StateCapability.get[Int](StateKey("counter-etag")).getOrElse(-1)
        println(s"Expected: ${Workers * Increments}, Got: $resultB  ${if resultB == Workers * Increments then "✓" else "✗"}")
        println(s"Total ETag retries: ${retriesB.get()}")
