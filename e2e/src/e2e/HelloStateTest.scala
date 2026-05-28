package e2e

class HelloStateTest extends E2ESuite:

  var infra: OneShotInfra = null

  override def beforeAll(): Unit =
    super.beforeAll()
    infra = OneShotInfra.start("e2e-hello-state")

  override def afterAll(): Unit =
    if infra != null then infra.stop()
    super.afterAll()

  test("state CRUD: save / get / etag-update / transaction / bulk") {
    val out = infra.run(jarModule = "hello-state", mainClass = "hellostate.run")
    assert(out.contains("saved:        Some(Note(Hello from dapr4s!,1))"), clue(out))
    assert(out.contains("etag save:    ok"),                                clue(out))
    assert(out.contains("after update: Some(Note(Updated!,2))"),            clue(out))
    assert(out.contains("txn note-a:   Some(Note(A,1))"),                   clue(out))
    assert(out.contains("txn note-b:   Some(Note(B,1))"),                   clue(out))
    assert(out.contains("txn original: None"),                              clue(out))
    assert(out.contains("note-a=Some(Note(A,1))"),                         clue(out))
    assert(out.contains("note-b=Some(Note(B,1))"),                         clue(out))
  }
