package e2e

class Example12ConversationTest extends E2ESuite:

  val infra = OneShotInfra("e2e-conversation")
  override def munitFixtures = List(infra)

  test("echo component round-trips alpha1 (converse/converseMany) and alpha2 (converseAlpha2)") {
    val out = infra.run(jarModule = "conversation", mainClass = "conversation.run")
    assert(out.contains("""converse("hello world"): hello world"""), clue(out))
    // The echo component reflects each prompt back; assert all three appear.
    assert(out.contains("alpha") && out.contains("beta") && out.contains("gamma"), clue(out))
    assert(out.contains("converseAlpha2(user: ping) -> ping"), clue(out))
  }
