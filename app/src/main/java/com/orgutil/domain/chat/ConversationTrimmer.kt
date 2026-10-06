package com.orgutil.domain.chat

/** Result of a mid-run trim; the drop is always reported, never silent. */
data class TrimOutcome(
    val messages: List<LlmMessage>,
    val droppedExchanges: Int,
    val droppedTokens: Long
)

/**
 * Pair-safe mid-run trimmer for the in-memory conversation. An "exchange"
 * starts at a [LlmMessage.User] and runs to the next one, so an
 * Assistant(tool_use) turn and its ToolResults answer are always in the
 * same atomic unit. When the accumulated conversation exceeds the budget,
 * oldest whole exchanges are dropped; the newest exchange (holding the
 * user's current goal) and the persisted-summary holder message always
 * survive. Everything dropped here is still stored in chat_message - the
 * request is trimmed, not the history.
 */
object ConversationTrimmer {

    fun trim(conversation: List<LlmMessage>, budgetTokens: Long): TrimOutcome {
        val total = conversation.sumOf(::estimate)
        if (total <= budgetTokens) return TrimOutcome(conversation, 0, 0)

        // Exchange start indexes = every User message index. The summary
        // holder (always the head) is not part of any droppable exchange.
        val summaryHolderCount = conversation.takeWhile { it is LlmMessage.User && it.isSummaryHolder }.size
        val body = conversation.drop(summaryHolderCount)
        val starts = body.indices.filter { body[it] is LlmMessage.User }
        if (starts.size <= 1) return TrimOutcome(conversation, 0, 0) // only the newest exchange - keep it

        // Greedy: drop oldest whole exchanges while over budget; always keep
        // at least the newest exchange.
        var dropCount = 0
        var droppedTokens = 0L
        var kept = total
        while (kept > budgetTokens && dropCount < starts.size - 1) {
            val from = starts[dropCount]
            val to = starts.getOrNull(dropCount + 1) ?: body.size
            val exchangeCost = (from until to).sumOf { estimate(body[it]) }
            kept -= exchangeCost
            droppedTokens += exchangeCost
            dropCount++
        }
        val cutAt = starts.getOrNull(dropCount) ?: body.size
        val keptBody = body.drop(cutAt)
        return TrimOutcome(
            messages = conversation.take(summaryHolderCount) + keptBody,
            droppedExchanges = dropCount,
            droppedTokens = droppedTokens
        )
    }

    private fun estimate(message: LlmMessage): Long = when (message) {
        is LlmMessage.User -> TokenEstimator.estimate(message.text)
        is LlmMessage.Assistant ->
            TokenEstimator.estimate(message.text) + message.toolUses.sumOf {
                TokenEstimator.estimate(it.name) + TokenEstimator.estimate(it.args.toString())
            }
        is LlmMessage.ToolResults ->
            message.results.sumOf { TokenEstimator.estimate(it.content) + TokenEstimator.estimate(it.toolName) }
    }
}
