package e2e

class Example13BindingsTest extends E2ESuite:

  override val munitTimeout = scala.concurrent.duration.Duration(120, "s")

  // The Kafka binding lives in components/13-bindings/ (kept out of the shared
  // components/ dir so other examples' daprd don't dial Kafka). Injected only for
  // this fixture; the broker is reached by its compose service name (localhost:9092
  // → kafka:9092).
  private def kafkaComponent: String =
    os.read(Harness.ProjectRoot / "components" / "13-bindings" / "orders-queue.yaml")
      .replace("localhost:9092", "kafka:9092")

  // The runnable example calls the real jsonplaceholder.typicode.com; the E2E points
  // the same binding at the in-network WireMock stub (docker-compose.13-bindings.yml)
  // so CI never depends on — or hammers — a third-party endpoint.
  private val jsonPlaceholderStub =
    """apiVersion: dapr.io/v1alpha1
      |kind: Component
      |metadata:
      |  name: jsonplaceholder
      |spec:
      |  type: bindings.http
      |  version: v1
      |  metadata:
      |    - name: url
      |      value: "http://httpstub:8080"
      |    - name: direction
      |      value: "output"
      |""".stripMargin

  val infra = ServerInfra(
    appId = "e2e-bindings",
    jarModule = "bindings",
    mainClass = "bindings.bindingsApp",
    composeFileName = "docker-compose.13-bindings.yml",
    extraComponents = Map(
      "orders-queue.yaml" -> kafkaComponent,
      "jsonplaceholder.yaml" -> jsonPlaceholderStub,
    ),
    extraEnv =
      () => Map("WIREMOCK_MAPPINGS" -> (Harness.ProjectRoot / "e2e" / "docker" / "wiremock" / "mappings").toString),
    // Give the Kafka input-binding consumer a moment to join its group.
    postStart = _ => Thread.sleep(3_000),
  )
  override def munitFixtures = List(infra)

  test("output binding (HTTP): POST /create returns the post jsonplaceholder created") {
    val (code, body) = DaprHttp.post(
      infra.daprHttpPort,
      "/v1.0/invoke/e2e-bindings/method/create",
      """{"title":"hello","body":"from dapr4s","userId":1}""",
    )
    assertEquals(code, 200, clue(body))
    // The HTTP binding's create response carries id 101 (jsonplaceholder's behaviour,
    // mirrored by the stub) and echoes the title we sent.
    assert(body.contains("\"id\":101"), clue(body))
    assert(body.contains("hello"), clue(body))
  }

  test("input+output binding (Kafka → HTTP): enqueue triggers a fetch persisted to state") {
    // Produce a PostRef onto the Kafka queue via the OUTPUT side of the binding; the INPUT
    // side (BindingRoute) receives it back, fetches /posts/5 from jsonplaceholder via the
    // HTTP output binding, and saves the Post under post-5.
    //
    // We re-enqueue each round: the Kafka input-binding consumer may still be joining its
    // group when the first message is produced (and would then miss it), so producing again
    // once it's ready guarantees delivery. The BindingRoute fetch+save is idempotent.
    val deadline = System.currentTimeMillis() + 90_000
    var found = Option.empty[String]
    while found.isEmpty && System.currentTimeMillis() < deadline do
      val (code, _) = DaprHttp.post(
        infra.daprHttpPort,
        "/v1.0/invoke/e2e-bindings/method/enqueue",
        """{"postId":5}""",
      )
      assertEquals(code, 200)
      Thread.sleep(3_000)
      // We match on the post title, not `"id":5`: state is read here via the raw Dapr
      // HTTP API, where the stored value is a JSON string literal (escaped quotes), so a
      // bare `"id":5` substring wouldn't appear. The title has no inner quotes to escape.
      val (sc, body) = DaprHttp.get(infra.daprHttpPort, "/v1.0/state/statestore/post-5")
      if sc == 200 && body.contains("nesciunt quas odio") then found = Some(body)

    assert(found.isDefined, clue("post-5 was never persisted by the Kafka-triggered BindingRoute"))
  }
