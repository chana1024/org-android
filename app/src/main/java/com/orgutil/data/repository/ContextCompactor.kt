package com.orgutil.data.repository

import com.orgutil.data.database.entity.ChatMessageEntity
import com.orgutil.domain.chat.LlmMessage

/** Persistence seam for compaction summaries (ChatRepository implements it). */
interface CompactionSummaryStore {
    /** Latest persisted summary for the session: summary text to its upToCreatedAt boundary. */
    suspend fun latest(sessionId: String): Pair<String, Long>?

    suspend fun save(sessionId: String, summary: String, upToCreatedAt: Long)
}

/** Outcome of building one LLM request; the UI surfaces the distinction. */
sealed class CompactionResult(val messages: List<LlmMessage>) {
    /** Budget fine - full (not-yet-summarized) transcript replayed. */
    class Full(messages: List<LlmMessage>) : CompactionResult(messages)

    /** Older exchanges summarized and persisted; request = summary + recent window. */
    class Applied(
        messages: List<LlmMessage>,
        val summary: String,
        val summarizedCount: Int,
        val keptCount: Int
    ) : CompactionResult(messages)

    /**
     * Summarization failed - the request falls back to the pairing-safe
     * recent window (older exchanges NOT sent this turn but still stored in
     * chat_message; nothing is silently deleted). [reason] is user-visible.
     */
    class FallbackWindow(
        messages: List<LlmMessage>,
        val reason: String,
        val droppedCount: Int,
        val keptCount: Int
    ) : CompactionResult(messages)
}

/**
 * Builds model requests under the context budget. The original
 * chat_message rows are NEVER modified - compaction only adds a persisted
 * summary row and changes what the NEXT request contains.
 *
 * [summarizer] receives the previous summary text (if any) plus the
 * exchanges to fold in, and returns null on failure - a null never
 * fabricates context, it switches to [CompactionResult.FallbackWindow].
 */
class ContextCompactor(
    private val summaryStore: CompactionSummaryStore,
    private val configProvider: () -> CompactionConfig,
    private val summarizer: suspend (previousSummary: String?, excerpts: List<ChatMessageEntity>) -> String?
) {

    suspend fun buildRequest(sessionId: String, messages: List<ChatMessageEntity>): CompactionResult {
        val config = configProvider()
        val previous = summaryStore.latest(sessionId)
        val plan = CompactionPlanner.plan(messages, previous?.second, config)

        if (!plan.shouldCompact) {
            return CompactionResult.Full(withSummaryPrefix(previous?.first, TranscriptReplay.build(plan.keepWindow)))
        }

        val summary = summarizer(previous?.first, plan.toSummarize)
        return if (summary != null) {
            summaryStore.save(sessionId, summary, plan.toSummarize.last().createdAt)
            CompactionResult.Applied(
                messages = withSummaryPrefix(summary, TranscriptReplay.build(plan.keepWindow)),
                summary = summary,
                summarizedCount = plan.toSummarize.size,
                keptCount = plan.keepWindow.size
            )
        } else {
            // FM-C2: never fabricate a summary; keep the pairing-safe window,
            // keep the OLD summary prefix if one exists, surface the failure.
            CompactionResult.FallbackWindow(
                messages = withSummaryPrefix(previous?.first, TranscriptReplay.build(plan.keepWindow)),
                reason = "摘要生成失败：本次请求仅使用未压缩的近期窗口（完整历史仍保留在会话记录中）",
                droppedCount = plan.toSummarize.size,
                keptCount = plan.keepWindow.size
            )
        }
    }

    private fun withSummaryPrefix(summary: String?, messages: List<LlmMessage>): List<LlmMessage> =
        if (summary.isNullOrBlank()) messages
        else listOf(
            LlmMessage.User(
                text = "[Earlier conversation summary - older messages were compacted]\n$summary",
                isSummaryHolder = true
            )
        ) + messages
}
