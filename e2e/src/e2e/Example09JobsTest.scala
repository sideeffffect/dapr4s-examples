package e2e

class Example09JobsTest extends E2ESuite:

  override val munitTimeout = scala.concurrent.duration.Duration(60, "s")

  val infra = ServerInfra(
    appId = "e2e-jobs",
    jarModule = "jobs",
    mainClass = "jobs.jobsApp",
    // give the scheduler a moment to connect before we schedule a job
    postStart = _ => Thread.sleep(2_000),
  )
  override def munitFixtures = List(infra)

  test("scheduled job fires back to its JobRoute, which persists the payload") {
    // Ask the app (via its `schedule` service method) to schedule DemoJob ~2s out.
    val (code, _) = DaprHttp.post(
      infra.daprHttpPort,
      "/v1.0/invoke/e2e-jobs/method/schedule",
      "\"user-42\"",
    )
    assertEquals(code, 200)

    // The JobRoute handler saves the delivered payload to this state key.
    val deadline = System.currentTimeMillis() + 30_000
    var found = Option.empty[String]
    while found.isEmpty && System.currentTimeMillis() < deadline do
      val (sc, body) = DaprHttp.get(infra.daprHttpPort, "/v1.0/state/statestore/job-result-DemoJob")
      if sc == 200 && body.contains("user-42") then found = Some(body)
      else Thread.sleep(500)

    assert(found.isDefined, clue(s"job payload was never persisted; last poll empty"))
  }
