package conversation

import dapr4s.*

// ── Capture-checked pure module ───────────────────────────────────────────────
// ConversationCapability is an ExclusiveCapability acquired by
// DaprCapability.conversation(...).  The demo runs against the built-in
// `conversation.echo` component, which echoes each prompt straight back — so the
// "completions" are deterministic and need no real LLM provider.  Both wire APIs
// are exercised: alpha1 (converse / converseMany) and alpha2 (converseAlpha2).
// ─────────────────────────────────────────────────────────────────────────────

val EchoComponent = ConversationComponentName("echo")

case class ConversationResult(
    single: String,
    many: List[String],
    chatReply: Option[String],
)

object ConversationDemoApp:
  def apply()(using DaprCapability): ConversationResult =
    DaprCapability.conversation(EchoComponent):
      val single = ConversationCapability.converse("hello world")

      val many = ConversationCapability.converseMany(Seq("alpha", "beta", "gamma"))

      val resp = ConversationCapability.converseAlpha2(Seq(ConversationMessage.user("ping")))
      val chatReply = resp.outputs.headOption
        .flatMap(_.choices.headOption)
        .map(_.message.content)

      ConversationResult(single, many, chatReply)
