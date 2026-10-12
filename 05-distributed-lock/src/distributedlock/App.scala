package distributedlock

import dapr4s.*
import dapr4s.lock.*
import dapr4s.state.*
import scala.concurrent.duration.FiniteDuration

// ── Capture-checked pure module ───────────────────────────────────────────────
// LockCapability.Instance and StateCapability.Instance are ExclusiveCapabilities: the
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
    foreignUnlock: Option[UnlockStatus], // B unlocks while A holds it — expected LockBelongsToOthers
)

// Unlock reports its outcome as a value, not an exception. The match is exhaustive: a new
// UnlockStatus case in dapr4s would be a compile error here.
def describe(status: UnlockStatus): String = status match
  case UnlockStatus.Success             => "Success"
  case UnlockStatus.LockNotFound        => "LockNotFound"
  case UnlockStatus.LockBelongsToOthers => "LockBelongsToOthers"
  case UnlockStatus.InternalError       => "InternalError"

object DistributedLockApp:
  def apply(
      lockExpiry: FiniteDuration,
      shortExpiry: FiniteDuration,
  )(using DaprCapability, JsonCodec[Int]): LockDemoResult =
    DaprCapability.state(StateStoreName("statestore")):
      DaprCapability.lock(LockStoreName("lockstore")):
        val resource = LockResourceId("my-resource")
        val counter = StateStoreKey("lock-counter")

        StateCapability.Instance.save(counter, 0)

        val N = 5
        for i <- 1 to N do
          val owner = LockOwner(s"worker-$i")
          if LockCapability.Instance.tryLock(resource, owner, expiry = lockExpiry) then
            try
              val v = StateCapability.Instance.get[Int](counter).getOrElse(0)
              StateCapability.Instance.save(counter, v + 1)
            finally LockCapability.Instance.unlock(resource, owner)

        val finalCounter = StateCapability.Instance.get[Int](counter).getOrElse(-1)

        val ownerA = LockOwner("process-A")
        val ownerB = LockOwner("process-B")
        val (secondAcquire, foreignUnlock) =
          if LockCapability.Instance.tryLock(resource, ownerA, expiry = lockExpiry) then
            val second = LockCapability.Instance.tryLock(resource, ownerB, expiry = shortExpiry)
            // B does not own the lock, so B cannot release it: A keeps holding it.
            val foreign = LockCapability.Instance.unlock(resource, ownerB)
            LockCapability.Instance.unlock(resource, ownerA)
            (second, Some(foreign))
          else (false, None)

        val afterRelease = LockCapability.Instance.tryLock(resource, ownerB, expiry = lockExpiry)
        if afterRelease then LockCapability.Instance.unlock(resource, ownerB)

        LockDemoResult(finalCounter, N, secondAcquire, afterRelease, foreignUnlock)
