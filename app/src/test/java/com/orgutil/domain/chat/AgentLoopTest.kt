package com.orgutil.domain.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the loop semantics against a scripted LLM and in-memory tools:
 * - APPROVAL mode: MEDIUM call suspends until approved / denied (denial is
 *   a normal tool result fed back to the model);
 * - AUTO mode: even HIGH tools run with ZERO approvals and no limits;
 * - every executed call lands in the audit trail with the right
 *   decisionSource.
 */
class AgentLoopTest {

    private class FakeTool(
        override val name: String,
        override val policy: ToolPolicy,
        private val executed: MutableList<String>
    ) : AgentTool {
        override val description = "fake"
        override val parametersSchema = kotlinx.serialization.json.JsonObject(emptyMap())
        override fun describeArgs(args: kotlinx.serialization.json.JsonObject) = "args-of-$name"
        override suspend fun execute(args: kotlinx.serialization.json.JsonObject): ToolResult {
            executed.add(name)
            return ToolResult.Ok("done-$name", affectedPaths = listOf("a.org"), bytesWritten = 12)
        }
    }

    private class FakeCatalog(toolList: List<AgentTool>) : AgentToolCatalog {
        override val tools: List<AgentTool> = toolList
        override fun byName(name: String): AgentTool? = tools.find { it.name == name }
    }

    private class FakeTranscriptStore : TranscriptStore {
        var nextId = 0
        val audit = mutableListOf<AuditEntry>()
        val toolApprovalStates = mutableListOf<Pair<String, ApprovalState>>()

        override suspend fun appendUserMessage(sessionId: String, text: String): String = "m${nextId++}"
        override suspend fun appendAssistantMessage(
            sessionId: String, text: String, toolUses: List<ToolUseBlock>, riskLevels: Map<String, String>
        ): String = "m${nextId++}"
        override suspend fun appendToolCallMessage(
            sessionId: String, toolUse: ToolUseBlock, argsDigest: String,
            riskLevel: RiskLevel, approvalState: ApprovalState, decisionSource: DecisionSource?
        ): String = "m${nextId++}"
        override suspend fun updateToolApprovalState(messageId: String, approvalState: ApprovalState, decisionSource: DecisionSource) {
            toolApprovalStates.add(messageId to approvalState)
        }
        override suspend fun updateToolResult(messageId: String, summary: String, isError: Boolean) = Unit
        override suspend fun appendAudit(entry: AuditEntry) { audit.add(entry) }
        override suspend fun buildLlmMessages(sessionId: String): List<LlmMessage> = emptyList()
        override suspend fun voidPendingApprovals(sessionId: String) = Unit
        override fun observeMessages(sessionId: String): Flow<List<ChatMessageView>> = flowOf(emptyList())
    }

    private class ScriptedLlm(private val turns: List<List<ToolUseBlock>>) : LlmClient {
        override val modelName = "fake"
        var turn = 0
        override fun stream(systemPrompt: String, messages: List<LlmMessage>, tools: List<AgentTool>): Flow<LlmEvent> = flow {
            val uses = turns.getOrNull(turn).orEmpty()
            turn++
            uses.forEach { emit(LlmEvent.ToolUseArrived(it)) }
            emit(LlmEvent.TurnCompleted("text$turn", uses.size))
        }
    }

    private fun args() = kotlinx.serialization.json.JsonObject(emptyMap())

    private fun loop(
        tools: List<AgentTool>,
        turns: List<List<ToolUseBlock>>,
        transcript: FakeTranscriptStore,
        gate: DefaultApprovalGate = DefaultApprovalGate(),
        grants: SessionGrantStore = SessionGrantStore()
    ): AgentLoop = AgentLoop(
        catalog = FakeCatalog(tools),
        approvalGate = gate,
        sessionGrants = grants,
        transcriptStore = transcript,
        llmClient = ScriptedLlm(turns)
    )

    private val writePolicy = ToolPolicy(risk = RiskLevel.MEDIUM, sessionGrantAllowed = true)
    private val deletePolicy = ToolPolicy(risk = RiskLevel.HIGH, sessionGrantAllowed = false)

    @Test
    fun `approval mode - medium tool waits then executes on approve once`() = runTest(UnconfinedTestDispatcher()) {
        val executed = mutableListOf<String>()
        val transcript = FakeTranscriptStore()
        val gate = DefaultApprovalGate()
        val tool = FakeTool("org_write_file", writePolicy, executed)
        val agentLoop = loop(
            tools = listOf(tool),
            turns = listOf(listOf(ToolUseBlock("t1", "org_write_file", args()))),
            transcript = transcript,
            gate = gate
        )
        agentLoop.modeProvider = { AgentMode.APPROVAL }

        val events = mutableListOf<ChatStreamEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            agentLoop.run("s1", "hi").collect { events.add(it) }
        }
        testScheduler.runCurrent()

        // Suspended on the approval card, nothing executed yet.
        assertTrue(executed.isEmpty())
        assertTrue(events.any { it is ChatStreamEvent.ToolCallPending })

        gate.answer("t1", ApprovalDecision.ApproveOnce)
        testScheduler.runCurrent()
        job.join() // runTest no longer needs explicit advanced time here

        assertEquals(listOf("org_write_file"), executed)
        assertTrue(events.any { it is ChatStreamEvent.RunFinished })
        val auditEntry = transcript.audit.single { it.tool == "org_write_file" && it.result != "DENIED" }
        assertEquals(DecisionSource.USER_ONCE, auditEntry.decisionSource)
        assertEquals(AgentMode.APPROVAL, auditEntry.mode)
    }

    @Test
    fun `approval mode - denial feeds back to the model without executing`() = runTest(UnconfinedTestDispatcher()) {
        val executed = mutableListOf<String>()
        val transcript = FakeTranscriptStore()
        val gate = DefaultApprovalGate()
        val agentLoop = loop(
            tools = listOf(FakeTool("org_write_file", writePolicy, executed)),
            turns = listOf(listOf(ToolUseBlock("t1", "org_write_file", args()))),
            transcript = transcript,
            gate = gate
        )
        agentLoop.modeProvider = { AgentMode.APPROVAL }

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            agentLoop.run("s1", "hi").collect { }
        }
        testScheduler.runCurrent()
        assertTrue(executed.isEmpty())

        gate.answer("t1", ApprovalDecision.Deny(reason = "no"))
        testScheduler.runCurrent()
        job.join() // wait for runTest scope completion

        assertTrue(executed.isEmpty())
        assertTrue(transcript.audit.any { it.result == "DENIED" && it.approvalState == ApprovalState.DENIED })
    }

    @Test
    fun `auto mode - high risk tool executes with zero approvals`() = runTest(UnconfinedTestDispatcher()) {
        val executed = mutableListOf<String>()
        val transcript = FakeTranscriptStore()
        val gate = DefaultApprovalGate()
        val agentLoop = loop(
            tools = listOf(FakeTool("org_delete_file", deletePolicy, executed)),
            turns = listOf(listOf(ToolUseBlock("t1", "org_delete_file", args()))),
            transcript = transcript,
            gate = gate
        )
        agentLoop.modeProvider = { AgentMode.AUTO }

        var sawPending = false
        agentLoop.run("s1", "hi").collect { event ->
            if (event is ChatStreamEvent.ToolCallPending) sawPending = true
        }

        assertTrue(!sawPending)
        assertEquals(listOf("org_delete_file"), executed)
        val auditEntry = transcript.audit.single { it.result == "OK" }
        assertEquals(DecisionSource.AUTO_POLICY, auditEntry.decisionSource)
        assertEquals(AgentMode.AUTO, auditEntry.mode)
    }

    @Test
    fun `auto mode - no limits - repeated writes all run`() = runTest(UnconfinedTestDispatcher()) {
        val executed = mutableListOf<String>()
        val transcript = FakeTranscriptStore()
        val tool = FakeTool("org_write_file", writePolicy, executed)
        val agentLoop = loop(
            tools = listOf(tool),
            turns = listOf(
                listOf(ToolUseBlock("t1", "org_write_file", args())),
                listOf(ToolUseBlock("t2", "org_write_file", args())),
                listOf(ToolUseBlock("t3", "org_write_file", args())),
                listOf(ToolUseBlock("t4", "org_write_file", args())),
                emptyList()
            ),
            transcript = transcript
        )
        agentLoop.modeProvider = { AgentMode.AUTO }

        agentLoop.run("s1", "hi").collect { }

        // Five turns, four executions, no downgrade, no block: AUTO has no
        // count limits by definition.
        assertEquals(4, executed.size)
        assertEquals(4, transcript.audit.count { it.result == "OK" })
    }

    @Test
    fun `unknown tool is reported as an error result`() = runTest(UnconfinedTestDispatcher()) {
        val transcript = FakeTranscriptStore()
        val agentLoop = loop(
            tools = emptyList(),
            turns = listOf(listOf(ToolUseBlock("t1", "bash", args()))),
            transcript = transcript
        )
        agentLoop.modeProvider = { AgentMode.AUTO }

        var failed = false
        agentLoop.run("s1", "hi").collect { event ->
            if (event is ChatStreamEvent.RunFailed) failed = true
        }
        assertTrue(!failed) // unknown tool is a tool error, not a loop crash
    }

    @Test
    fun `session grant approves later calls without asking`() = runTest(UnconfinedTestDispatcher()) {
        val executed = mutableListOf<String>()
        val transcript = FakeTranscriptStore()
        val gate = DefaultApprovalGate()
        // Same digest for both calls -> same grant key.
        val tool = FakeTool("org_write_file", writePolicy, executed)
        val agentLoop = loop(
            tools = listOf(tool),
            turns = listOf(
                listOf(ToolUseBlock("t1", "org_write_file", args())),
                listOf(ToolUseBlock("t2", "org_write_file", args())),
                emptyList()
            ),
            transcript = transcript,
            gate = gate
        )
        agentLoop.modeProvider = { AgentMode.APPROVAL }

        var pendingCount = 0
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            agentLoop.run("s1", "hi").collect { event ->
                if (event is ChatStreamEvent.ToolCallPending) {
                    pendingCount++
                    gate.answer(event.requestId, ApprovalDecision.ApproveSession)
                }
            }
        }
        testScheduler.runCurrent()
        job.join() // wait for runTest scope completion

        assertEquals(1, pendingCount)
        assertEquals(2, executed.size)
    }
}
