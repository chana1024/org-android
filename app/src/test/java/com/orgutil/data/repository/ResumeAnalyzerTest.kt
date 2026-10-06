package com.orgutil.data.repository

import com.orgutil.data.database.entity.ChatMessageEntity
import com.orgutil.domain.chat.ToolUseBlock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins FM-R1..R4: after a killed run the transcript tail must classify
 * exactly what was left behind - unresolved approvals (voidable), approved
 * tool calls with no recorded result (side-effect state UNKNOWN - must be
 * surfaced, never assumed), and the prompt to offer for resume.
 */
class ResumeAnalyzerTest {

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

    private fun toolRow(
        name: String,
        useId: String,
        approvalState: String?,
        result: String? = null,
        argsJson: String = """{"path":"gtd.org"}"""
    ) = ChatMessageEntity(
        id = "m${id++}", sessionId = "s", role = "tool", content = result ?: "",
        toolName = name, toolUseId = useId, toolArgsJson = argsJson,
        toolResultSummary = result, approvalState = approvalState, createdAt = ts++
    )

    private fun use(id: String) =
        ToolUseBlock(id = id, name = "x", args = buildJsonObject { put("path", "gtd.org") })

    @Test
    fun `interrupted tail reports pending approvals unknown-effect tools and prompt`() {
        val messages = listOf(
            user("整理一下 gtd.org"),
            assistant("我先看看文件", use("tu_1"), use("tu_2"), use("tu_3")),
            toolRow("org_read_file", "tu_1", "APPROVED", result = "read ok"),
            toolRow("org_write_file", "tu_2", "APPROVED"), // killed while executing
            toolRow("org_delete_file", "tu_3", "PENDING")  // approval card never answered
        )

        val report = ResumeAnalyzer.analyze(messages)

        assertEquals("整理一下 gtd.org", report.lastUserPrompt)
        assertEquals(1, report.pendingApprovals)
        assertEquals(listOf(ResumeIssue("org_write_file", """{"path":"gtd.org"}""")), report.unknownEffectTools)
        assertTrue(report.hasAnyUserMessage)
    }

    @Test
    fun `finished runs have nothing to resume`() {
        val messages = listOf(
            user("hi"),
            assistant("hello"),
            user("bye"),
            assistant("done")
        )

        val report = ResumeAnalyzer.analyze(messages)

        assertEquals("bye", report.lastUserPrompt)
        assertEquals(0, report.pendingApprovals)
        assertTrue(report.unknownEffectTools.isEmpty())
    }

    @Test
    fun `approved tool with recorded result is not unknown-effect`() {
        val messages = listOf(
            user("x"),
            assistant("working", use("tu_1")),
            toolRow("org_write_file", "tu_1", "APPROVED", result = "wrote 12 bytes")
        )

        val report = ResumeAnalyzer.analyze(messages)

        assertTrue(report.unknownEffectTools.isEmpty())
    }

    @Test
    fun `empty transcript cannot resume`() {
        val report = ResumeAnalyzer.analyze(emptyList())

        assertEquals(null, report.lastUserPrompt)
        assertTrue(!report.hasAnyUserMessage)
    }

    @Test
    fun `voided and denied approvals are not pending and not unknown-effect`() {
        val messages = listOf(
            user("clean"),
            assistant("working", use("tu_1"), use("tu_2")),
            toolRow("org_delete_file", "tu_1", "VOIDED"),
            toolRow("git_sync", "tu_2", "DENIED")
        )

        val report = ResumeAnalyzer.analyze(messages)

        assertEquals(0, report.pendingApprovals)
        assertTrue(report.unknownEffectTools.isEmpty())
    }
}
