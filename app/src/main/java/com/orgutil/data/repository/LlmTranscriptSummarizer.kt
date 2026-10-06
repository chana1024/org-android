package com.orgutil.data.repository

import com.orgutil.data.database.entity.ChatMessageEntity
import com.orgutil.domain.chat.LlmClient
import com.orgutil.domain.chat.LlmEvent
import com.orgutil.domain.chat.LlmMessage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produces the persisted compaction summary using the configured LLM.
 * Renders bounded per-message excerpts (never the full history - the point
 * is to shrink), and returns null on any failure so the compactor falls
 * back instead of storing a broken summary.
 */
@Singleton
class LlmTranscriptSummarizer @Inject constructor() {

    suspend fun summarize(
        llm: LlmClient,
        config: CompactionConfig,
        previousSummary: String?,
        excerpts: List<ChatMessageEntity>
    ): String? {
        if (excerpts.isEmpty()) return previousSummary
        val prompt = buildPrompt(previousSummary, excerpts, config.excerptChars)
        val text = StringBuilder()
        var failed = false
        return try {
            llm.stream(
                systemPrompt = SUMMARY_SYSTEM_PROMPT,
                messages = listOf(LlmMessage.User(prompt)),
                tools = emptyList()
            ).collect { event ->
                when (event) {
                    is LlmEvent.TextDelta -> text.append(event.delta)
                    is LlmEvent.ToolUseArrived -> Unit
                    // Summarizer calls carry no tools; hosted search cannot
                    // occur here, but the branches keep the when exhaustive.
                    is LlmEvent.WebSearchStarted, is LlmEvent.WebSearchFinished -> Unit
                    is LlmEvent.TurnCompleted -> Unit
                    is LlmEvent.Failed -> failed = true
                    is LlmEvent.ProviderSwitched, is LlmEvent.ProviderActive -> Unit
                }
            }.let {
                val result = text.toString().trim()
                if (failed || result.isBlank()) null else result
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildPrompt(previousSummary: String?, excerpts: List<ChatMessageEntity>, excerptChars: Int): String =
        buildString {
            append("Summarize the following earlier part of a conversation between the user and an org-mode notes assistant. ")
            append("Keep: user goals/decisions, file paths touched, tool outcomes that matter later (created/modified/deleted files, git sync results), and unresolved questions. ")
            append("Drop pleasantries and raw file dumps. Answer in the conversation's dominant language, plain text, at most 300 words.")
            if (!previousSummary.isNullOrBlank()) {
                append("\n\nPrevious summary (already covers the oldest history):\n")
                append(previousSummary)
            }
            append("\n\nConversation to fold in:")
            excerpts.forEach { message ->
                append("\n").append(render(message, excerptChars))
            }
        }

    private fun render(message: ChatMessageEntity, excerptChars: Int): String {
        val content = if (message.role == "user") ChatMessageContextCodec.forModel(message.content) else message.content
        val body = content.take(excerptChars)
        return when (message.role) {
            "user" -> "user: $body"
            "assistant" -> if (message.toolUsesJson != null) "assistant (requested tools): $body" else "assistant: $body"
            "tool" -> "tool ${message.toolName ?: "?"} result: ${message.toolResultSummary?.take(excerptChars) ?: body}"
            else -> "${message.role}: $body"
        }
    }

    companion object {
        private val SUMMARY_SYSTEM_PROMPT = """
            You compress conversation history for a note-taking assistant.
            Output ONLY the summary text - no preamble, no markdown fences.
        """.trimIndent()
    }
}
