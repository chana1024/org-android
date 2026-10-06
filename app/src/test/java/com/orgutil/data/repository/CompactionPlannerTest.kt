package com.orgutil.data.repository

import com.orgutil.data.database.entity.ChatMessageEntity
import com.orgutil.domain.chat.TokenEstimator
import com.orgutil.domain.chat.ToolUseBlock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins FM-C1/C3: compaction planning must (a) never split an exchange -
 * an assistant tool_use turn and its tool result rows move together, (b)
 * keep unresolved (PENDING) calls in the recent window, (c) only trigger
 * past the configured budget threshold, (d) never re-summarize messages
 * already covered by a persisted summary.
 */
class CompactionPlannerTest {

    private var ts = 0L
    private var id = 0

    private fun user(text: String) =
        ChatMessageEntity(id = "m${id++}", sessionId = "s", role = "user", content = text, createdAt = ts++)

    private fun assistant(text: String, vararg uses: ToolUseBlock) =
        ChatMessageEntity(
            id = "m${id++}", sessionId = "s", role = "assistant", content = text,
            toolUsesJson = TranscriptReplay.encodeToolUses(uses.toList()),
            createdAt = ts++
        )

    private fun toolRow(name: String, useId: String, approvalState: String? = "APPROVED", result: String? = "ok") =
        ChatMessageEntity(
            id = "m${id++}", sessionId = "s", role = "tool", content = result ?: "",
            toolName = name, toolUseId = useId, toolResultSummary = result,
            approvalState = approvalState, createdAt = ts++
        )

    private fun use(id: String) =
        ToolUseBlock(id = id, name = "org_list_files", args = buildJsonObject { put("path", "gtd.org") })

    /** exchange i: user + assistant(tool_use) + tool result - all pair-safe units. */
    private fun exchange(i: Int, pending: Boolean = false): List<ChatMessageEntity> = listOf(
        user("question number $i with some padding text to give it weight"),
        assistant("answer $i", use("tu_$i")),
        toolRow("org_list_files", "tu_$i", approvalState = if (pending) "PENDING" else "APPROVED")
    )

    @Test
    fun `under budget no compaction is planned`() {
        val messages = exchange(1) + exchange(2)

        val plan = CompactionPlanner.plan(messages, previousSummaryUpTo = null, config = CompactionConfig())

        assertTrue(!plan.shouldCompact)
        assertEquals(messages, plan.keepWindow)
        assertTrue(plan.toSummarize.isEmpty())
    }

    @Test
    fun `over budget older exchanges are summarized and exchange atomicity holds`() {
        // 10 exchanges, tiny budget -> must compact.
        val messages = (1..10).flatMap { exchange(it) }

        val plan = CompactionPlanner.plan(
            messages, previousSummaryUpTo = null,
            config = CompactionConfig(budgetTokens = 200, triggerFraction = 0.5, keepExchanges = 3)
        )

        assertTrue(plan.shouldCompact)

        // Atomicity (FM-C1): every exchange is wholly in toSummarize or wholly
        // in keepWindow; an assistant tool_use turn is never separated from
        // its tool result row.
        val summarizeIds = plan.toSummarize.map { it.id }.toSet()
        val keepIds = plan.keepWindow.map { it.id }.toSet()
        assertTrue(summarizeIds.intersect(keepIds).isEmpty())
        assertEquals(messages.map { it.id }.toSet(), summarizeIds + keepIds)
        plan.toSummarize.forEach { row ->
            if (row.role == "tool") {
                val assistantPartner = plan.toSummarize.any {
                    it.role == "assistant" && it.toolUsesJson?.contains(row.toolUseId!!) == true
                }
                assertTrue("tool row ${row.id} must keep its assistant partner", assistantPartner)
            }
        }

        // Keep window = last 3 exchanges (9 rows), in order, same row ids.
        val expectedKeepIds = messages.takeLast(9).map { it.id }
        assertEquals(expectedKeepIds, plan.keepWindow.map { it.id })
    }

    @Test
    fun `exchange with pending approval always stays in the keep window`() {
        // Last exchange carries a PENDING (unresolved) approval.
        val messages = (1..8).flatMap { exchange(it) } + exchange(9, pending = true)

        val plan = CompactionPlanner.plan(
            messages, previousSummaryUpTo = null,
            config = CompactionConfig(budgetTokens = 200, triggerFraction = 0.5, keepExchanges = 3)
        )

        assertTrue(plan.shouldCompact)
        val pendingRowsKept = plan.keepWindow.filter { it.approvalState == "PENDING" }
        assertEquals(1, pendingRowsKept.size)
        assertTrue(
            "pending approval rows must never be summarized away",
            plan.toSummarize.none { it.approvalState == "PENDING" }
        )
    }

    @Test
    fun `messages covered by an existing summary are excluded from both windows`() {
        val first = exchange(1) + exchange(2)
        val boundary = first.last().createdAt
        val fresh = exchange(3) + exchange(4)
        val messages = first + fresh

        val plan = CompactionPlanner.plan(
            messages, previousSummaryUpTo = boundary,
            config = CompactionConfig(budgetTokens = 1_000_000, keepExchanges = 6)
        )

        assertTrue(!plan.shouldCompact)
        // Only the not-yet-summarized messages enter the request window.
        assertEquals(fresh.map { it.id }, plan.keepWindow.map { it.id })
    }

    @Test
    fun `cannot compact when everything already fits the keep window`() {
        val messages = exchange(1) + exchange(2)

        val plan = CompactionPlanner.plan(
            messages, previousSummaryUpTo = null,
            config = CompactionConfig(budgetTokens = 10, triggerFraction = 0.1, keepExchanges = 6)
        )

        // Over threshold but nothing can be summarized (keepExchanges covers all).
        assertTrue(!plan.shouldCompact)
        assertEquals(messages.map { it.id }, plan.keepWindow.map { it.id })
    }

    @Test
    fun `estimator counts cjk heavier than latin`() {
        val cjk = TokenEstimator.estimate("笔记内容整理")
        val latin = TokenEstimator.estimate("abcdefghij")

        assertTrue(cjk > latin / 2)
        assertTrue(TokenEstimator.estimate("") == 0L)
    }
}
