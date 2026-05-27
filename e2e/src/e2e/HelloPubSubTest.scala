package e2e

class HelloPubSubTest extends E2ESuite:
  val AppPort  = 8083
  val DaprPort = 3503
  val AppId    = "e2e-pubsub-sub"

  var serverProc: os.SubProcess = null

  override def beforeAll(): Unit =
    super.beforeAll()
    serverProc = Harness.spawnServer(
      appId     = AppId,
      jarModule = "hello-pubsub",
      mainClass = "hellopubsub.subscriber",
      appPort   = AppPort,
      daprPort  = DaprPort,
    )
    Harness.waitForPort(DaprPort)
    Harness.waitForPort(AppPort)
    Thread.sleep(2_000)  // let Dapr register subscriptions

  override def afterAll(): Unit =
    if serverProc != null then Harness.stopApp(AppId, serverProc)
    super.afterAll()

  test("subscription endpoint is registered") {
    val (status, body) = DaprHttp.appGet(AppPort, "/dapr/subscribe")
    assertEquals(status, 200)
    assert(body.contains("hello-topic"), clue(body))
    assert(body.contains("pubsub"),      clue(body))
  }

  test("publishing a message returns 204") {
    val event = """{"from":"e2e","text":"hello","sequenceNo":1}"""
    val (status, _) = DaprHttp.post(DaprPort, "/v1.0/publish/pubsub/hello-topic", event)
    assertEquals(status, 204)
  }

  test("subscriber stays alive after 5 messages") {
    for i <- 1 to 5 do
      val event = s"""{"from":"e2e","text":"msg","sequenceNo":$i}"""
      DaprHttp.post(DaprPort, "/v1.0/publish/pubsub/hello-topic", event)
    Thread.sleep(1_000)
    assert(serverProc.isAlive(), "subscriber process crashed")
  }
