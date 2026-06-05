package conversation

import dapr4s.*

// ── Capture-checked pure module ───────────────────────────────────────────────
// ConversationCapability is an ExclusiveCapability acquired by
// DaprCapability.conversation(...).  The demo runs against the built-in
// `conversation.echo` component, which echoes each prompt straight back — so the
// "completions" are deterministic and need no real LLM provider.  `converse`
// holds a multi-message exchange: message roles in, choices + usage out.
// ─────────────────────────────────────────────────────────────────────────────

val EchoComponent = ConversationComponentName("echo")

case class ConversationDemoResult(
    reply: Option[String],
    withRoles: Option[String],
)

object ConversationDemoApp:
  def apply()(using DaprCapability): ConversationDemoResult =
    DaprCapability.conversation(EchoComponent):
      // A single-message exchange — the echo component reflects the prompt back.
      val resp = ConversationCapability.converse(Seq(ConversationMessage.user("ping")))

      // The same API carries multi-message context with explicit roles.
      val resp2 = ConversationCapability.converse(
        Seq(
          ConversationMessage.system("be terse"),
          ConversationMessage.user("hello world"),
        ),
      )

      ConversationDemoResult(firstContent(resp), firstContent(resp2))

  private def firstContent(resp: ConversationResponse): Option[String] =
    resp.outputs.headOption.flatMap(_.choices.headOption).map(_.message.content)
