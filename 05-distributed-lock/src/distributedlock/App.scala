package distributedlock

import dapr4s.*
import scala.concurrent.duration.FiniteDuration

// ── Capture-checked pure module ───────────────────────────────────────────────
// LockCapability and StateCapability are ExclusiveCapabilities: the
// compiler prevents them from being captured in lambdas passed to other threads.
// Counter state is stored as a simple Int; JsonCodec[Int] is passed from the
// shell so codec derivation stays out of this module.  Lock expiries are passed
// in from the shell so this module stays clock-free (FiniteDuration cannot be
// constructed from safe code).
// ─────────────────────────────────────────────────────────────────────────────

case class LockDemoResult(
    finalCounter: Int,
    expected: Int,
    secondAcquire: Boolean, // attempted while A holds lock — expected false
    afterRelease: Boolean, // attempted after A releases  — expected true
)

object DistributedLockApp:
  def apply(
      lockExpiry: FiniteDuration,
      shortExpiry: FiniteDuration,
  )(using DaprCapability, JsonCodec[Int]): LockDemoResult =
    DaprCapability.state(StateStoreName("statestore")):
      DaprCapability.lock(LockStoreName("lockstore")):
        val resource = LockResourceId("my-resource")
        val counter = StateStoreKey("lock-counter")

        StateCapability.save(counter, 0)

        val N = 5
        for i <- 1 to N do
          val owner = LockOwner(s"worker-$i")
          if LockCapability.tryLock(resource, owner, expiry = lockExpiry) then
            try
              val v = StateCapability.get[Int](counter).getOrElse(0)
              StateCapability.save(counter, v + 1)
            finally LockCapability.unlock(resource, owner)

        val finalCounter = StateCapability.get[Int](counter).getOrElse(-1)

        val ownerA = LockOwner("process-A")
        val ownerB = LockOwner("process-B")
        val secondAcquire =
          if LockCapability.tryLock(resource, ownerA, expiry = lockExpiry) then
            val second = LockCapability.tryLock(resource, ownerB, expiry = shortExpiry)
            LockCapability.unlock(resource, ownerA)
            second
          else false

        val afterRelease = LockCapability.tryLock(resource, ownerB, expiry = lockExpiry)
        if afterRelease then LockCapability.unlock(resource, ownerB)

        LockDemoResult(finalCounter, N, secondAcquire, afterRelease)
