package e2e

class ServiceInvocationTest extends E2ESuite:
  val AppPort  = 8084
  val DaprPort = 3504
  val AppId    = "e2e-callee"

  var serverProc: os.SubProcess = null

  override def beforeAll(): Unit =
    super.beforeAll()
    serverProc = Harness.spawnServer(
      appId     = AppId,
      jarModule = "service-invocation",
      mainClass = "serviceinvocation.callee",
      appPort   = AppPort,
      daprPort  = DaprPort,
    )
    Harness.waitForPort(DaprPort)
    Harness.waitForPort(AppPort)

  override def afterAll(): Unit =
    if serverProc != null then Harness.stopApp(AppId, serverProc)
    super.afterAll()

  // The callee's dapr4s HTTP server exposes routes at /{methodName}.
  // Tests call the app port directly — the same interface the Dapr sidecar uses.

  test("greet en") {
    val (status, body) = DaprHttp.appPost(AppPort, "/greet", """{"name":"Alice","language":"en"}""")
    assertEquals(status, 200)
    val json = ujson.read(body)
    assertEquals(json("greeting").str, "Hello, Alice!")
    assertEquals(json("from").str,     "greeting-service")
  }

  test("greet es") {
    val (status, body) = DaprHttp.appPost(AppPort, "/greet", """{"name":"Bob","language":"es"}""")
    assertEquals(status, 200)
    assertEquals(ujson.read(body)("greeting").str, "¡Hola, Bob!")
  }

  test("greet fr") {
    val (status, body) = DaprHttp.appPost(AppPort, "/greet", """{"name":"Carol","language":"fr"}""")
    assertEquals(status, 200)
    assertEquals(ujson.read(body)("greeting").str, "Bonjour, Carol!")
  }

  test("greet unknown language falls back to Hi") {
    val (status, body) = DaprHttp.appPost(AppPort, "/greet", """{"name":"X","language":"jp"}""")
    assertEquals(status, 200)
    assertEquals(ujson.read(body)("greeting").str, "Hi, X!")
  }

  test("stats tracks requests and languages") {
    DaprHttp.appPost(AppPort, "/greet", """{"name":"A","language":"de"}""")
    DaprHttp.appPost(AppPort, "/greet", """{"name":"B","language":"en"}""")
    val (status, body) = DaprHttp.appGet(AppPort, "/stats")
    assertEquals(status, 200)
    val json  = ujson.read(body)
    val count = json("totalRequests").num.toLong
    val langs = json("languages").arr.map(_.str).toSet
    assert(count >= 2,            s"expected ≥2 requests, got $count")
    assert(langs.contains("de"),  clue(langs))
    assert(langs.contains("en"),  clue(langs))
  }
