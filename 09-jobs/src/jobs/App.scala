package jobs

import dapr4s.*
import dapr4s.derivation.*

// ── Capture-checked pure module ───────────────────────────────────────────────
// Jobs has two halves, both shown here:
//   • client side  — JobsCapability.scheduleOnce asks the sidecar's scheduler to
//     fire a job at a future time.  Invoked from the `schedule` service method.
//   • trigger side — when the job fires, the sidecar POSTs it back to the app at
//     /job/<name>, dispatched to the matching JobRoute.  The handler persists the
//     delivered payload to the state store so the outcome is observable.
//
// Both JobsCapability and StateCapability are ExclusiveCapabilities introduced by
// the enclosing DaprCapability scopes; the route lambdas capture them, and the
// @assumeSafe boundaries inside InvocationRoute/JobRoute erase the capture set.
// ─────────────────────────────────────────────────────────────────────────────

val StateStore = StateStoreName("statestore")
val DemoJob = JobName("demo-job")

def resultKey: StateStoreKey = StateStoreKey(s"job-result-${DemoJob.value}")

// The scheduler is described as a trait; dapr4s.derivation implements it. The method name
// maps (via @name) to the JobName; a `dueTime: Instant` parameter selects scheduleOnce.
trait Scheduler:
  @name("demo-job")
  def scheduleDemoJob(data: String, dueTime: java.time.Instant)(using JobsCapability, JsonCodec[String]): Unit
object Scheduler extends Jobs.Derived[Scheduler]

// Schedule DemoJob to fire two seconds from now, carrying `payload`.
def scheduleDemo(payload: String)(using JobsCapability, JsonCodec[String]): String =
  Scheduler.derive.scheduleDemoJob(payload, java.time.Instant.now().plusSeconds(2))
  payload

// Invoked by the sidecar when DemoJob fires; records the payload for inspection.
def onJobFired(payload: String)(using StateCapability, JsonCodec[String]): Unit =
  StateCapability.save(resultKey, payload)

object JobsServerApp:
  def apply()(using DaprCapability, JsonCodec[String]): DaprApp =
    DaprCapability.state(StateStore):
      DaprCapability.jobs:
        DaprApp(
          invocations = List(
            InvocationRoute[String, String](InvocationMethodName("schedule"))(scheduleDemo),
          ),
          jobs = List(
            JobRoute[String](DemoJob)(onJobFired),
          ),
        )
