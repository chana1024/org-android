package com.orgutil.domain.chat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins mid-run conversation trimming (FM-C1 applied to the in-memory
 * loop): when the accumulated in-run conversation blows the budget,
 * oldest whole exchanges are dropped - never an Assistant(tool_use) turn
 * without its ToolResults partner - and the newest exchange (which holds
 * the user's current goal) always survives. Dropping is REPORTED via the
 * outcome (never silent), and the persisted-summary holder message is
 * never trimmed away.
 */
class ConversationTrimmerTest {

    private fun exchange(i: Int, text: String = "x".repeat(300)): List<LlmMessage> = listOf(
        LlmMessage.User("question $i $text"),
        LlmMessage.Assistant("answer $i", listOf(ToolUseBlock("tu_$i", "org_list_files", buildJsonObject { put("path", "a.org") }))),
        LlmMessage.ToolResults(listOf(ToolResultBlock("tu_$i", "org_list_files", "result $i $text", isError = false)))
    )

    private fun final() = LlmMessage.Assistant("final answer", emptyList())

    @Test
    fun `under budget nothing is dropped`() {
        val conversation = exchange(1) + listOf(final())

        val outcome = ConversationTrimmer.trim(conversation, budgetTokens = 10_000)

        assertEquals(conversation, outcome.messages)
        assertEquals(0, outcome.droppedExchanges)
    }

    @Test
    fun `over budget oldest exchanges drop whole, pairing survives, drop is reported`() {
        val conversation = (1..5).flatMap { exchange(it) } + listOf(final())

        val outcome = ConversationTrimmer.trim(conversation, budgetTokens = 400)

        assertTrue("must drop something", outcome.messages.size < conversation.size)
        assertTrue("drop must be reported, not silent", outcome.droppedExchanges > 0)
        assertTrue(outcome.droppedTokens > 0)
        // Pairing: every Assistant tool_use turn has its ToolResults partner.
        val keptUses = outcome.messages.filterIsInstance<LlmMessage.Assistant>().flatMap { it.toolUses }.map { it.id }
        val keptResults = outcome.messages.filterIsInstance<LlmMessage.ToolResults>().flatMap { it.results }.map { it.toolUseId }
        assertEquals(keptUses.sorted(), keptResults.sorted())
        // The newest exchange always survives.
        assertTrue(keptUses.contains("tu_5"))
        assertTrue(outcome.messages.last() is LlmMessage.Assistant)
    }

    @Test
    fun `single over-budget exchange is returned as-is`() {
        val conversation = exchange(1) + listOf(final())

        val outcome = ConversationTrimmer.trim(conversation, budgetTokens = 5)

        assertEquals(conversation, outcome.messages)
        assertEquals(0, outcome.droppedExchanges)
    }

    @Test
    fun `summary holder message is never trimmed away`() {
        val summary = LlmMessage.User("[Earlier conversation summary]\nolder stuff", isSummaryHolder = true)
        val conversation = listOf(summary) + (1..5).flatMap { exchange(it) } + listOf(final())

        val outcome = ConversationTrimmer.trim(conversation, budgetTokens = 200)

        // Even when everything droppable is dropped, the summary stays.
        val keptSummary = outcome.messages.firstOrNull { it is LlmMessage.User && (it as LlmMessage.User).isSummaryHolder }
        assertTrue("summary holder must survive trimming", keptSummary != null)
        assertTrue(outcome.droppedExchanges > 0)
        // Newest exchange still there.
        assertTrue(
            outcome.messages.filterIsInstance<LlmMessage.Assistant>().flatMap { it.toolUses }.any { it.id == "tu_5" }
        )
    }
}
