package e2e

class Example04ServiceInvocationTest extends E2ESuite:

  val infra = ServerInfra(
    // The caller addresses the callee by this app-id (see CallerApp).
    appId = "greeting-service",
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

  test("caller: typed invoke, invokeHttp status and InvokeHttpException") {
    val out = infra.runInApp("serviceinvocation.caller")
    assert(out.contains("Hello, Alice!  (from: greeting-service)"), clue(out))
    assert(out.contains("Hi, Eve!  (from: greeting-service)"), clue(out))
    assert(out.contains("invokeHttp no-such-method: HTTP 404"), clue(out))
    assert(out.contains("invoke no-such-method: InvokeHttpException(HTTP 404)"), clue(out))
  }
