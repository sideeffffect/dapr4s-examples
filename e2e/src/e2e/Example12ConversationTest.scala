package e2e

class Example12ConversationTest extends E2ESuite:

  val infra = OneShotInfra("e2e-conversation")
  override def munitFixtures = List(infra)

  test("echo component round-trips a converse exchange") {
    val out = infra.run(jarModule = "conversation", mainClass = "conversation.run")
    // The echo component reflects each prompt back.
    assert(out.contains("converse(user: ping) -> ping"), clue(out))
    assert(out.contains("hello world"), clue(out))
  }
