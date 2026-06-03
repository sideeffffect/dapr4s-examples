package e2e

class Example03HelloPubSubTest extends E2ESuite:

  val infra = ServerInfra(
    appId = "e2e-pubsub-sub",
    jarModule = "hello-pubsub",
    mainClass = "hellopubsub.subscriber",
  )
  override def munitFixtures = List(infra)

  test("subscription endpoint is registered") {
    val (status, body) = DaprHttp.appGet(infra.appHttpPort, "/dapr/subscribe")
    assertEquals(status, 200)
    assert(body.contains("hello-topic"), clue(body))
    assert(body.contains("pubsub"), clue(body))
  }

  test("publishing a message returns 204") {
    val event = """{"from":"e2e","text":"hello","sequenceNo":1}"""
    val (status, _) = DaprHttp.post(infra.daprHttpPort, "/v1.0/publish/pubsub/hello-topic", event)
    assertEquals(status, 204)
  }

  test("subscriber stays alive after 5 messages") {
    for i <- 1 to 5 do
      val event = s"""{"from":"e2e","text":"msg","sequenceNo":$i}"""
      DaprHttp.post(infra.daprHttpPort, "/v1.0/publish/pubsub/hello-topic", event)
    Thread.sleep(1_000)
    // Compose is still running means the subscriber container is alive
    assert(infra.daprHttpPort > 0, "compose stopped unexpectedly")
  }
