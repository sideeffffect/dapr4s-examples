package distributedlock

import dapr4s.*

// ── Impure shell ──────────────────────────────────────────────────────────────

@main def run(): Unit =
  println("=== 05 distributed-lock ===\n")
  DaprRuntime.run(DaprRuntimeConfig()):
    val r = distributedLockApp()
    println(s"Counter after ${r.expected} sequential workers: ${r.finalCounter}  ${if r.finalCounter == r.expected then "✓" else "✗"}")
    println()
    println("--- Double-acquire attempt ---")
    println(s"process-B tryLock while A holds it: ${r.secondAcquire}  (expected false)  ${if !r.secondAcquire then "✓" else "✗"}")
    println(s"process-B tryLock after A releases: ${r.afterRelease}  (expected true)   ${if r.afterRelease then "✓" else "✗"}")
  println("\n[distributed-lock] done.")
