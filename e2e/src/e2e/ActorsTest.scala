package e2e

class ActorsTest extends E2ESuite:

  override val munitTimeout = scala.concurrent.duration.Duration(60, "s")

  val infra = ServerInfra(
    appId = "e2e-actors",
    jarModule = "actors",
    mainClass = "actors.actorApp",
    // placement service needs a moment to register the actor type after daprd connects
    postStart = _ => Thread.sleep(3_000),
  )
  override def munitFixtures = List(infra)

  private def actorMethod(actorId: String, method: String, body: String = "{}"): (Int, String) =
    DaprHttp.put(
      infra.daprHttpPort,
      s"/v1.0/actors/CounterActor/$actorId/method/$method",
      body,
    )

  test("initial state is zero") {
    val id = "e2e-actor-init"
    val (status, body) = actorMethod(id, "get")
    assertEquals(status, 200)
    val json = ujson.read(body)
    assertEquals(json("count").num.toInt, 0)
    assertEquals(json("totalIncrements").num.toInt, 0)
  }

  test("increment adds to count") {
    val id = "e2e-actor-incr"
    actorMethod(id, "increment", """{"amount":5}""")
    val (status, body) = actorMethod(id, "get")
    assertEquals(status, 200)
    val json = ujson.read(body)
    assertEquals(json("count").num.toInt, 5)
    assertEquals(json("totalIncrements").num.toInt, 1)
  }

  test("multiple increments accumulate") {
    val id = "e2e-actor-multi"
    actorMethod(id, "increment", """{"amount":3}""")
    actorMethod(id, "increment", """{"amount":7}""")
    val (_, body) = actorMethod(id, "get")
    val json = ujson.read(body)
    assertEquals(json("count").num.toInt, 10)
    assertEquals(json("totalIncrements").num.toInt, 2)
  }

  test("reset clears the count") {
    val id = "e2e-actor-reset"
    actorMethod(id, "increment", """{"amount":42}""")
    actorMethod(id, "reset")
    val (_, body) = actorMethod(id, "get")
    assertEquals(ujson.read(body)("count").num.toInt, 0)
  }
