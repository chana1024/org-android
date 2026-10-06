package com.orgutil.data.repository

import com.orgutil.data.database.entity.ChatMessageEntity
import com.orgutil.domain.chat.LlmMessage
import com.orgutil.domain.chat.ToolUseBlock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the transcript replay against the API contract the client has to
 * satisfy: every replayed tool_use id is answered exactly once by the
 * immediately following ToolResults message, interrupted runs synthesize
 * the result the model would have seen, and pre-v6 rows (no persisted
 * blocks / ids) degrade to the old text-only replay.
 */
class TranscriptReplayTest {

    private var nextTs = 0L
    private var nextId = 0

    private fun user(text: String) =
        ChatMessageEntity(id = "m${nextId++}", sessionId = "s", role = "user", content = text, createdAt = nextTs++)

    private fun assistant(text: String, vararg uses: ToolUseBlock) =
        ChatMessageEntity(
            id = "m${nextId++}", sessionId = "s", role = "assistant", content = text,
            toolUsesJson = TranscriptReplay.encodeToolUses(uses.toList()),
            createdAt = nextTs++
        )

    private fun toolRow(use: ToolUseBlock, result: String? = null, isError: Boolean = false, approvalState: String? = "APPROVED") =
        ChatMessageEntity(
            id = "m${nextId++}", sessionId = "s", role = "tool", content = result ?: "",
            toolName = use.name, toolUseId = use.id,
            toolResultSummary = when {
                result == null -> null
                isError -> "✗ $result"
                else -> result
            },
            approvalState = approvalState,
            createdAt = nextTs++
        )

    private fun use(id: String, name: String = "org_list_files") =
        ToolUseBlock(id = id, name = name, args = buildJsonObject { put("path", "gtd.org") })

    private fun resultOf(message: LlmMessage.ToolResults, toolUseId: String) =
        message.results.first { it.toolUseId == toolUseId }

    @Test
    fun `replay pairs persisted tool uses with their results`() {
        val u1 = use("tu_1", "org_list_files")
        val u2 = use("tu_2", "org_search")
        val messages = listOf(
            user("list my gtd files"),
            assistant("let me look", u1, u2),
            toolRow(u1, result = "3 files"),
            toolRow(u2, result = "no match", isError = true)
        )

        val replayed = TranscriptReplay.build(messages)

        assertEquals(3, replayed.size)
        val assistant = replayed[1] as LlmMessage.Assistant
        assertEquals(listOf(u1, u2), assistant.toolUses)
        val results = replayed[2] as LlmMessage.ToolResults
        assertEquals("3 files", resultOf(results, "tu_1").content)
        assertFalse(resultOf(results, "tu_1").isError)
        assertTrue(resultOf(results, "tu_2").isError)
    }

    @Test
    fun `denied call synthesizes the decline the model saw live`() {
        val u1 = use("tu_1", "org_write_file")
        val messages = listOf(
            user("rewrite gtd.org"),
            assistant("opening the file", u1),
            toolRow(u1, result = null, approvalState = "DENIED")
        )

        val replayed = TranscriptReplay.build(messages)

        val results = replayed[2] as LlmMessage.ToolResults
        val denied = resultOf(results, "tu_1")
        assertEquals("User declined this action.", denied.content)
        assertTrue(denied.isError)
    }

    @Test
    fun `crash before tool rows are written answers each use as interrupted`() {
        val u1 = use("tu_1")
        val u2 = use("tu_2")
        val messages = listOf(
            user("list files"),
            assistant("looking", u1, u2)
        )

        val replayed = TranscriptReplay.build(messages)

        val results = replayed[2] as LlmMessage.ToolResults
        assertEquals(2, results.results.size)
        assertTrue(results.results.all { it.isError && it.content == "(interrupted: no result was recorded)" })
    }

    @Test
    fun `voided and unresolved approvals synthesize cancellations`() {
        val voided = use("tu_1", "org_delete_file")
        val pending = use("tu_2", "git_sync")
        val approvedNoResult = use("tu_3", "org_write_file")
        val messages = listOf(
            user("clean up"),
            assistant("working", voided, pending, approvedNoResult),
            toolRow(voided, result = null, approvalState = "VOIDED"),
            toolRow(pending, result = null, approvalState = "PENDING"),
            toolRow(approvedNoResult, result = null, approvalState = "APPROVED")
        )

        val replayed = TranscriptReplay.build(messages)

        val results = replayed[2] as LlmMessage.ToolResults
        assertEquals("Cancelled before execution.", resultOf(results, "tu_1").content)
        assertEquals("Interrupted: the approval was never resolved.", resultOf(results, "tu_2").content)
        // FM-R3: an approved call with no recorded result was in flight when
        // the process died - the model must be told the state is UNKNOWN,
        // never a fabricated success or a plain "no result".
        val unknown = resultOf(results, "tu_3")
        assertTrue(unknown.isError)
        assertTrue(unknown.content.contains("Execution state unknown"))
        assertTrue(unknown.content.contains("may or may not have completed"))
    }

    @Test
    fun `tool exchange flushes before the next run's user message`() {
        val u1 = use("tu_1")
        val messages = listOf(
            user("first"),
            assistant("checking", u1),
            toolRow(u1, result = "ok"),
            user("second"),
            assistant("all done")
        )

        val replayed = TranscriptReplay.build(messages)

        assertEquals(5, replayed.size)
        assertTrue(replayed[0] is LlmMessage.User)
        assertTrue(replayed[1] is LlmMessage.Assistant)
        assertTrue(replayed[2] is LlmMessage.ToolResults)
        assertTrue(replayed[3] is LlmMessage.User)
        assertTrue(replayed[4] is LlmMessage.Assistant)
        assertEquals("ok", resultOf(replayed[2] as LlmMessage.ToolResults, "tu_1").content)
        assertTrue((replayed[4] as LlmMessage.Assistant).toolUses.isEmpty())
    }

    @Test
    fun `pre-v6 rows replay as text only`() {
        val messages = listOf(
            ChatMessageEntity(id = "m0", sessionId = "s", role = "user", content = "hi", createdAt = 0),
            ChatMessageEntity(id = "m1", sessionId = "s", role = "assistant", content = "hello", createdAt = 1),
            ChatMessageEntity(
                id = "m2", sessionId = "s", role = "tool", content = "3 files",
                toolName = "org_list_files", toolUseId = null, approvalState = "APPROVED", createdAt = 2
            )
        )

        val replayed = TranscriptReplay.build(messages)

        assertEquals(2, replayed.size)
        assertTrue((replayed[1] as LlmMessage.Assistant).toolUses.isEmpty())
    }

    @Test
    fun `result rows that cannot pair with a pending use are dropped`() {
        // Defensive ordering: a tool row landing after the next user message
        // must not emit a tool_result with an unknown tool_use id.
        val u1 = use("tu_1")
        val messages = listOf(
            user("first"),
            assistant("checking", u1),
            user("second"),
            toolRow(u1, result = "late")
        )

        val replayed = TranscriptReplay.build(messages)

        assertEquals(4, replayed.size)
        val results = replayed[2] as LlmMessage.ToolResults
        assertTrue(resultOf(results, "tu_1").isError) // synthesized, "late" dropped
    }

    @Test
    fun `encodeToolUses round-trips blocks and yields null for empty turns`() {
        assertNull(TranscriptReplay.encodeToolUses(emptyList()))

        val u = use("tu_9", "org_parse_outline")
        val replayed = TranscriptReplay.build(
            listOf(assistant("text", u), toolRow(u, result = "outline ok"))
        )

        assertEquals(u, (replayed[0] as LlmMessage.Assistant).toolUses.single())
        assertEquals("outline ok", resultOf(replayed[1] as LlmMessage.ToolResults, "tu_9").content)
    }
}
