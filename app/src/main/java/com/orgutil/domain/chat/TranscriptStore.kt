package com.orgutil.domain.chat

import kotlinx.coroutines.flow.Flow

/**
 * Persistence contract for the agent loop. Tool calls are persisted as
 * messages with approval metadata, and every call also lands in the audit
 * trail - in both modes, without exception.
 */
interface TranscriptStore {

    suspend fun appendUserMessage(sessionId: String, text: String): String

    /** Persists the finished assistant turn (text + requested tool calls). */
    suspend fun appendAssistantMessage(
        sessionId: String,
        text: String,
        toolUses: List<ToolUseBlock>,
        riskLevels: Map<String, String>
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
    val isStreaming: Boolean = false
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
