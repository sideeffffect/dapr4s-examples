package jobs

import dapr4s.*

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
// @assumeSafe boundaries inside InvokeRoute/JobRoute erase the capture set.
// ─────────────────────────────────────────────────────────────────────────────

val StateStore = StateStoreName("statestore")
val DemoJob = JobName("DemoJob")

def resultKey: StateStoreKey = StateStoreKey(s"job-result-${DemoJob.value}")

// Schedule DemoJob to fire two seconds from now, carrying `payload`.
def scheduleDemo(payload: String)(using JobsCapability, JsonCodec[String]): String =
  JobsCapability.scheduleOnce(DemoJob, payload, java.time.Instant.now().plusSeconds(2))
  payload

// Invoked by the sidecar when DemoJob fires; records the payload for inspection.
def onJobFired(payload: String)(using StateCapability, JsonCodec[String]): Unit =
  StateCapability.save(resultKey, payload)

object JobsServerApp:
  def apply()(using DaprCapability, JsonCodec[String]): DaprApp =
    DaprCapability.state(StateStore):
      DaprCapability.jobs:
        DaprApp(
          invokeRoutes = List(
            InvokeRoute[String, String](InvokeMethodName("schedule"))(scheduleDemo),
          ),
          jobs = List(
            JobRoute[String](DemoJob)(onJobFired),
          ),
        )
