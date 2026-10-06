package com.orgutil.data.repository

import com.orgutil.data.database.entity.ChatMessageEntity
import com.orgutil.domain.chat.LlmMessage
import com.orgutil.domain.chat.ToolUseBlock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins FM-C2 (summary failure must not silently drop context) and the
 * success path: the built request is persisted-summary + pairing-safe
 * recent window; the original chat_message rows are NEVER mutated; a
 * failed summarization falls back to a bounded window with an explicit
 * status instead of quietly losing the exchange.
 */
class ContextCompactorTest {

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

    private fun toolRow(name: String, useId: String) =
        ChatMessageEntity(
            id = "m${id++}", sessionId = "s", role = "tool", content = "result of $useId",
            toolName = name, toolUseId = useId, approvalState = "APPROVED", createdAt = ts++
        )

    private fun use(id: String) =
        ToolUseBlock(id = id, name = "org_list_files", args = buildJsonObject { put("path", "a.org") })

    private fun exchange(i: Int, filler: String = "x".repeat(400)) = listOf(
        user("question $i $filler"),
        assistant("answer $i", use("tu_$i")),
        toolRow("org_list_files", "tu_$i")
    )

    private class FakeSummaryStore : CompactionSummaryStore {
        val saved = mutableListOf<Triple<String, String, Long>>()
        private var existing: Pair<String, Long>? = null
        override suspend fun latest(sessionId: String): Pair<String, Long>? = existing
        override suspend fun save(sessionId: String, summary: String, upToCreatedAt: Long) {
            existing = summary to upToCreatedAt
            saved.add(Triple(sessionId, summary, upToCreatedAt))
        }
    }

    private val config = CompactionConfig(budgetTokens = 300, triggerFraction = 0.5, keepExchanges = 2)

    @Test
    fun `success - summary persisted and request is summary plus recent window`() = runTest {
        val store = FakeSummaryStore()
        val messages = (1..5).flatMap { exchange(it) }
        val compactor = ContextCompactor(
            summaryStore = store,
            configProvider = { config },
            summarizer = { _, _ -> "SUMMARY OF OLD EXCHANGES" }
        )

        val result = compactor.buildRequest("s", messages)

        assertTrue(result is CompactionResult.Applied)
        result as CompactionResult.Applied
        assertEquals("SUMMARY OF OLD EXCHANGES", result.summary)
        assertEquals(1, store.saved.size)
        // Boundary covers exactly the summarized messages (exchanges 1..3).
        val summarized = messages.dropLast(exchange(0).size * 2) // last 2 kept
        assertEquals(summarized.last().createdAt, store.saved.single().third)
        // Request: summary as first user message, then replayed recent window
        // ending with the newest exchange intact.
        val first = result.messages.first() as LlmMessage.User
        assertTrue(first.text.contains("SUMMARY OF OLD EXCHANGES"))
        val lastUser = result.messages.filterIsInstance<LlmMessage.User>().last()
        assertTrue(lastUser.text.contains("question 5"))
        // Pairing inside the window.
        val uses = result.messages.filterIsInstance<LlmMessage.Assistant>().flatMap { it.toolUses }.map { it.id }
        val results = result.messages.filterIsInstance<LlmMessage.ToolResults>().flatMap { r -> r.results }.map { it.toolUseId }
        assertEquals(uses.sorted(), results.sorted())
    }

    @Test
    fun `summary failure - falls back to bounded window with explicit status, no silent loss marker`() = runTest {
        val store = FakeSummaryStore()
        val messages = (1..5).flatMap { exchange(it) }
        val compactor = ContextCompactor(
            summaryStore = store,
            configProvider = { config },
            summarizer = { _, _ -> null } // LLM summary call failed
        )

        val result = compactor.buildRequest("s", messages)

        assertTrue(result is CompactionResult.FallbackWindow)
        result as CompactionResult.FallbackWindow
        assertTrue(result.reason.isNotBlank())
        assertEquals(0, store.saved.size) // nothing persisted on failure
        // Window still contains the newest exchange.
        val lastUser = result.messages.filterIsInstance<LlmMessage.User>().last()
        assertTrue(lastUser.text.contains("question 5"))
        assertTrue(result.droppedCount > 0)
        assertTrue(result.keptCount > 0)
    }

    @Test
    fun `under budget - full transcript is replayed`() = runTest {
        val store = FakeSummaryStore()
        val messages = exchange(1) + exchange(2)
        val compactor = ContextCompactor(
            summaryStore = store,
            configProvider = { CompactionConfig(budgetTokens = 1_000_000, keepExchanges = 6) },
            summarizer = { _, _ -> "should not be called" }
        )

        val result = compactor.buildRequest("s", messages)

        assertTrue(result is CompactionResult.Full)
        assertEquals(6, result.messages.size) // 2 users + 2 assistants + 2 toolresults
        assertEquals(0, store.saved.size)
    }

    @Test
    fun `previous summary is respected - older window only, summary prefix kept on fallback`() = runTest {
        val saved = mutableListOf<Triple<String, String, Long>>()
        val old = exchange(1)
        val boundary = old.last().createdAt
        val fresh = (2..5).flatMap { exchange(it) }
        val messages = old + fresh
        // Seed an existing summary covering exchange 1.
        val seed = object : CompactionSummaryStore {
            override suspend fun latest(sessionId: String) = "OLD SUMMARY" to boundary
            override suspend fun save(sessionId: String, summary: String, upToCreatedAt: Long) {
                saved.add(Triple(sessionId, summary, upToCreatedAt))
            }
        }
        val compactor = ContextCompactor(
            summaryStore = seed,
            configProvider = { config },
            summarizer = { _, _ -> null } // fail -> fallback
        )

        val result = compactor.buildRequest("s", messages)

        assertTrue(result is CompactionResult.FallbackWindow)
        result as CompactionResult.FallbackWindow
        // Old summary stays as the request prefix even in fallback.
        val first = result.messages.first() as LlmMessage.User
        assertTrue(first.text.contains("OLD SUMMARY"))
        // Exchange 1 (already summarized) must not be replayed again.
        assertTrue(result.messages.none { it is LlmMessage.User && it.text.contains("question 1 ") })
    }
}
