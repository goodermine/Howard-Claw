package au.howardagent.agent

import au.howardagent.ui.chat.ChatMessage

object PromptBuilder {

    fun build(
        history: List<ChatMessage> = emptyList(),
        maxHistoryMessages: Int = 8
    ): String {
        val base = SystemPrompts.HOWARD_BASE.trimIndent()

        if (history.isEmpty()) return base

        val recentHistory = history
            .takeLast(maxHistoryMessages)
            .filter { it.role != "tool" }
            .joinToString("\n") { msg ->
                when (msg.role) {
                    "user"      -> "User: ${msg.content.take(200)}"
                    "assistant" -> "Howard: ${msg.content.take(200)}"
                    else        -> ""
                }
            }
            .trim()

        return if (recentHistory.isNotEmpty()) {
            "$base\n\n## Recent Context\n$recentHistory"
        } else {
            base
        }
    }
}
