package com.orgutil.domain.chat

import kotlinx.coroutines.flow.Flow

/**
 * Persistence contract for the agent loop. Tool calls are persisted as
 * messages with approval metadata, and every call also lands in the audit
 * trail - in both modes, without exception.
 *
 * HOSTED web search rows (role="tool", toolName = AgentLoop
 * .HOSTED_SEARCH_TOOL_NAME) are stored through the same append/update
 * calls but are provider-executed: they carry no audit entry and are never
 * replayed as tool_use/tool_result pairs.
 */
interface TranscriptStore {

    suspend fun appendUserMessage(sessionId: String, text: String): String

    /**
     * Persists the finished assistant turn (text + requested tool calls).
     * [nativeBlocksJson] carries the provider's own content blocks / output
     * items verbatim when the turn contained hosted search (exact replay on
     * the next request depends on them); null for ordinary turns.
     */
    suspend fun appendAssistantMessage(
        sessionId: String,
        text: String,
        toolUses: List<ToolUseBlock>,
        riskLevels: Map<String, String>,
        nativeBlocksJson: String? = null
    ): String

    suspend fun appendToolCallMessage(
        sessionId: String,
        toolUse: ToolUseBlock,
        argsDigest: String,
        riskLevel: RiskLevel,
        approvalState: ApprovalState,
        decisionSource: DecisionSource?
    ): String

    suspend fun updateToolApprovalState(
        messageId: String,
        approvalState: ApprovalState,
        decisionSource: DecisionSource
    )

    suspend fun updateToolResult(messageId: String, summary: String, isError: Boolean)

    suspend fun appendAudit(entry: AuditEntry)

    /** Full transcript rebuilt as LLM messages (user / assistant / tool results). */
    suspend fun buildLlmMessages(sessionId: String): List<LlmMessage>

    suspend fun voidPendingApprovals(sessionId: String)

    fun observeMessages(sessionId: String): Flow<List<ChatMessageView>>
}

/** UI-facing message view. */
data class ChatMessageView(
    val id: String,
    val role: String,
    val content: String,
    val toolName: String? = null,
    val toolArgsDigest: String? = null,
    val toolResultSummary: String? = null,
    val riskLevel: String? = null,
    val approvalState: ApprovalState? = null,
    val isStreaming: Boolean = false,
    val agendaReferences: List<AgendaContextReference> = emptyList(),
    /** Skill id when this user message invoked a slash-command skill. */
    val skillId: String? = null
)

data class AuditEntry(
    val sessionId: String,
    val messageId: String,
    val mode: AgentMode,
    val tool: String,
    val argsDigest: String,
    val decisionSource: DecisionSource,
    val approvalState: ApprovalState,
    val result: String,
    val affectedPaths: List<String>,
    val bytesWritten: Long,
    val durationMs: Long
)
