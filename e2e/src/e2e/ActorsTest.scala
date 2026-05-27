package e2e

class ActorsTest extends E2ESuite:
  val AppPort  = 8086
  val DaprPort = 3506

  // Each test case uses its own unique actor ID so tests are isolated without
  // needing a Redis flush between them.  A fresh actor ID → no prior state in
  // the state store → count/totalIncrements both start at 0.
  val AppId = "e2e-actors"

  var serverProc: os.SubProcess = null

  override def beforeAll(): Unit =
    super.beforeAll()
    serverProc = Harness.spawnServer(
      appId     = AppId,
      jarModule = "actors",
      mainClass = "actors.actorApp",
      appPort   = AppPort,
      daprPort  = DaprPort,
    )
    Harness.waitForPort(DaprPort)
    Harness.waitForPort(AppPort)
    Thread.sleep(3_000)  // placement service needs a moment to register the actor type

  override def afterAll(): Unit =
    if serverProc != null then Harness.stopApp(AppId, serverProc)
    super.afterAll()

  private def actorMethod(actorId: String, method: String, body: String = "{}"): (Int, String) =
    DaprHttp.put(
      DaprPort,
      s"/v1.0/actors/CounterActor/$actorId/method/$method",
      body,
    )

  test("initial state is zero") {
    val id = "e2e-actor-init"
    val (status, body) = actorMethod(id, "get")
    assertEquals(status, 200)
    val json = ujson.read(body)
    assertEquals(json("count").num.toInt,           0)
    assertEquals(json("totalIncrements").num.toInt, 0)
  }

  test("increment adds to count") {
    val id = "e2e-actor-incr"
    actorMethod(id, "increment", """{"amount":5}""")
    val (status, body) = actorMethod(id, "get")
    assertEquals(status, 200)
    val json = ujson.read(body)
    assertEquals(json("count").num.toInt,           5)
    assertEquals(json("totalIncrements").num.toInt, 1)
  }

  test("multiple increments accumulate") {
    val id = "e2e-actor-multi"
    actorMethod(id, "increment", """{"amount":3}""")
    actorMethod(id, "increment", """{"amount":7}""")
    val (_, body) = actorMethod(id, "get")
    val json = ujson.read(body)
    assertEquals(json("count").num.toInt,           10)
    assertEquals(json("totalIncrements").num.toInt, 2)
  }

  test("reset clears the count") {
    val id = "e2e-actor-reset"
    actorMethod(id, "increment", """{"amount":42}""")
    actorMethod(id, "reset")
    val (_, body) = actorMethod(id, "get")
    assertEquals(ujson.read(body)("count").num.toInt, 0)
  }
