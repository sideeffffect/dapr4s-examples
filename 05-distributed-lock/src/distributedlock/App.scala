package distributedlock

import dapr4s.*

// ── Capture-checked pure module ───────────────────────────────────────────────
// DistributedLockCapability and StateCapability are ExclusiveCapabilities: the
// compiler prevents them from being captured in lambdas passed to other threads.
// Counter state is stored as a simple Int; JsonCodec[Int] is passed from the
// shell so codec derivation stays out of this module.
// ─────────────────────────────────────────────────────────────────────────────

case class LockDemoResult(
    finalCounter: Int,
    expected: Int,
    secondAcquire: Boolean, // attempted while A holds lock — expected false
    afterRelease: Boolean, // attempted after A releases  — expected true
)

def distributedLockApp()(using DaprCapability, JsonCodec[Int]): LockDemoResult =
  DaprCapability.state(StoreName("statestore")):
    DaprCapability.lock(StoreName("lockstore")):
      val resource = LockResourceId("my-resource")
      val counter = StateKey("lock-counter")

      StateCapability.save(counter, 0)

      val N = 5
      for i <- 1 to N do
        val owner = LockOwner(s"worker-$i")
        if DistributedLockCapability.tryLock(resource, owner, expirySeconds = 10) then
          try
            val v = StateCapability.get[Int](counter).getOrElse(0)
            StateCapability.save(counter, v + 1)
          finally DistributedLockCapability.unlock(resource, owner)

      val finalCounter = StateCapability.get[Int](counter).getOrElse(-1)

      val ownerA = LockOwner("process-A")
      val ownerB = LockOwner("process-B")
      val secondAcquire =
        if DistributedLockCapability.tryLock(resource, ownerA, expirySeconds = 10) then
          val second = DistributedLockCapability.tryLock(resource, ownerB, expirySeconds = 1)
          DistributedLockCapability.unlock(resource, ownerA)
          second
        else false

      val afterRelease = DistributedLockCapability.tryLock(resource, ownerB, expirySeconds = 10)
      if afterRelease then DistributedLockCapability.unlock(resource, ownerB)

      LockDemoResult(finalCounter, N, secondAcquire, afterRelease)
