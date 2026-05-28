package e2e

class HelloStateTest extends E2ESuite:

  val infra = oneShot("e2e-hello-state")
  override def munitFixtures = List(infra)

  test("state CRUD: save / get / etag-update / transaction / bulk") {
    val out = infra().run(jarModule = "hello-state", mainClass = "hellostate.run")
    assert(out.contains("saved:        Some(Note(Hello from dapr4s!,1))"), clue(out))
    assert(out.contains("etag save:    ok"),                                clue(out))
    assert(out.contains("after update: Some(Note(Updated!,2))"),            clue(out))
    assert(out.contains("txn note-a:   Some(Note(A,1))"),                   clue(out))
    assert(out.contains("txn note-b:   Some(Note(B,1))"),                   clue(out))
    assert(out.contains("txn original: None"),                              clue(out))
    assert(out.contains("note-a=Some(Note(A,1))"),                         clue(out))
    assert(out.contains("note-b=Some(Note(B,1))"),                         clue(out))
  }
