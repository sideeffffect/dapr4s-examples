package e2e

class ServiceInvocationTest extends E2ESuite:

  val infra = ServerInfra(
    appId = "e2e-callee",
    jarModule = "service-invocation",
    mainClass = "serviceinvocation.callee",
  )
  override def munitFixtures = List(infra)

  test("greet en") {
    val (status, body) = DaprHttp.appPost(infra.appHttpPort, "/greet", """{"name":"Alice","language":"en"}""")
    assertEquals(status, 200)
    val json = ujson.read(body)
    assertEquals(json("greeting").str, "Hello, Alice!")
    assertEquals(json("from").str, "greeting-service")
  }

  test("greet es") {
    val (status, body) = DaprHttp.appPost(infra.appHttpPort, "/greet", """{"name":"Bob","language":"es"}""")
    assertEquals(status, 200)
    assertEquals(ujson.read(body)("greeting").str, "¡Hola, Bob!")
  }

  test("greet fr") {
    val (status, body) = DaprHttp.appPost(infra.appHttpPort, "/greet", """{"name":"Carol","language":"fr"}""")
    assertEquals(status, 200)
    assertEquals(ujson.read(body)("greeting").str, "Bonjour, Carol!")
  }

  test("greet unknown language falls back to Hi") {
    val (status, body) = DaprHttp.appPost(infra.appHttpPort, "/greet", """{"name":"X","language":"jp"}""")
    assertEquals(status, 200)
    assertEquals(ujson.read(body)("greeting").str, "Hi, X!")
  }

  test("stats tracks requests and languages") {
    DaprHttp.appPost(infra.appHttpPort, "/greet", """{"name":"A","language":"de"}""")
    DaprHttp.appPost(infra.appHttpPort, "/greet", """{"name":"B","language":"en"}""")
    val (status, body) = DaprHttp.appGet(infra.appHttpPort, "/stats")
    assertEquals(status, 200)
    val json = ujson.read(body)
    val count = json("totalRequests").num.toLong
    val langs = json("languages").arr.map(_.str).toSet
    assert(count >= 2, s"expected ≥2 requests, got $count")
    assert(langs.contains("de"), clue(langs))
    assert(langs.contains("en"), clue(langs))
  }
