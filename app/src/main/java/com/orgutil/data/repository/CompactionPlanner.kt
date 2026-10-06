package com.orgutil.data.repository

import com.orgutil.data.database.entity.ChatMessageEntity
import com.orgutil.domain.chat.LlmMessage
import com.orgutil.domain.chat.TokenEstimator

/**
 * Configurable context budget. No invented model limits - the budget is a
 * user-visible setting; defaults are conservative (32k input tokens,
 * trigger at 70%).
 */
data class CompactionConfig(
    val budgetTokens: Long = 32_000,
    val triggerFraction: Double = 0.7,
    val keepExchanges: Int = 6,
    /** Per-message excerpt cap for the summarizer prompt. */
    val excerptChars: Int = 600
)

data class CompactionPlan(
    val shouldCompact: Boolean,
    val toSummarize: List<ChatMessageEntity>,
    val keepWindow: List<ChatMessageEntity>,
    val estimatedTokens: Long
)

/**
 * Splits the transcript into pair-safe "exchanges" (a user message plus
 * everything after it up to the next user message) and decides what to
 * summarize versus keep when the estimated token weight passes the
 * configured threshold. Exchange atomicity guarantees a tool_use turn and
 * its tool results always travel together (FM-C1). Messages already
 * covered by a persisted summary ([previousSummaryUpTo]) are excluded from
 * both windows.
 */
object CompactionPlanner {

    fun plan(
        messages: List<ChatMessageEntity>,
        previousSummaryUpTo: Long?,
        config: CompactionConfig
    ): CompactionPlan {
        val unsummarized = if (previousSummaryUpTo == null) messages
        else messages.filter { it.createdAt > previousSummaryUpTo }

        val estimated = estimateMessages(unsummarized)
        val groups = groupIntoExchanges(unsummarized)

        if (estimated < config.budgetTokens * config.triggerFraction || groups.size <= config.keepExchanges) {
            return CompactionPlan(false, emptyList(), unsummarized, estimated)
        }

        val keepCount = config.keepExchanges.coerceAtLeast(1)
        val keepGroups = groups.takeLast(keepCount)
        val summarizeGroups = groups.dropLast(keepCount)

        // Unresolved approvals must never be summarized away (FM-C1): if any
        // landed outside the keep window, widen the window to cover them.
        val pendingInSummarize = summarizeGroups.any { group -> group.any { it.approvalState == "PENDING" } }
        return if (pendingInSummarize) {
            CompactionPlan(false, emptyList(), unsummarized, estimated)
        } else {
            CompactionPlan(
                shouldCompact = summarizeGroups.isNotEmpty(),
                toSummarize = summarizeGroups.flatten(),
                keepWindow = keepGroups.flatten(),
                estimatedTokens = estimated
            )
        }
    }

    /** Exchange i = rows from the i-th user message up to the next user message. */
    fun groupIntoExchanges(messages: List<ChatMessageEntity>): List<List<ChatMessageEntity>> {
        val groups = mutableListOf<MutableList<ChatMessageEntity>>()
        for (message in messages) {
            if (message.role == "user" || groups.isEmpty()) {
                groups.add(mutableListOf(message))
            } else {
                groups.last().add(message)
            }
        }
        return groups
    }

    fun estimateMessages(messages: List<ChatMessageEntity>): Long = messages.sumOf(::estimateMessage)

    private fun estimateMessage(message: ChatMessageEntity): Long = TokenEstimator.estimate(
        buildString {
            append(message.content)
            message.toolName?.let { append(it) }
            message.toolArgsJson?.let { append(it) }
            message.toolUsesJson?.let { append(it) }
            message.toolResultSummary?.let { append(it) }
        }
    )
}
