package com.orgutil.data.repository

import com.orgutil.data.database.dao.ChatDao
import com.orgutil.data.database.entity.ChatAuditLogEntity
import com.orgutil.data.database.entity.ChatMessageEntity
import com.orgutil.data.database.entity.ChatSessionEntity
import com.orgutil.domain.chat.AgentMode
import com.orgutil.domain.chat.ApprovalState
import com.orgutil.domain.chat.AuditEntry
import com.orgutil.domain.chat.ChatMessageView
import com.orgutil.domain.chat.DecisionSource
import com.orgutil.domain.chat.LlmMessage
import com.orgutil.domain.chat.RiskLevel
import com.orgutil.domain.chat.ToolUseBlock
import com.orgutil.domain.chat.TranscriptStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed transcript + audit trail. Also owns session lifecycle
 * (create/observe/latest/mode switching) for the UI.
 */
@Singleton
class ChatRepository @Inject constructor(
    private val chatDao: ChatDao
) : TranscriptStore {

    private val json = Json { ignoreUnknownKeys = true }

    // ---- session management (UI) ----

    suspend fun ensureSession(mode: AgentMode, autoArmed: Boolean): ChatSessionEntity {
        val latest = latestSession()
        if (latest != null) return latest
        val now = System.currentTimeMillis()
        val session = ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            title = "Chat",
            mode = mode.name,
            createdAt = now,
            autoArmedAt = if (mode == AgentMode.AUTO && autoArmed) now else null,
            updatedAt = now
        )
        chatDao.insertSession(session)
        return session
    }

    suspend fun latestSession(): ChatSessionEntity? = chatDao.observeLatestSession().firstOrNull()

    suspend fun setMode(sessionId: String, mode: AgentMode, autoArmed: Boolean) {
        val session = chatDao.getSession(sessionId) ?: return
        chatDao.updateSession(
            session.copy(
                mode = mode.name,
                autoArmedAt = if (mode == AgentMode.AUTO && autoArmed) System.currentTimeMillis() else session.autoArmedAt,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun observeSession(): Flow<ChatSessionEntity?> = chatDao.observeLatestSession()

    override fun observeMessages(sessionId: String): Flow<List<ChatMessageView>> =
        chatDao.observeMessages(sessionId).map { list -> list.map { it.toView() } }

    suspend fun getAudit(sessionId: String): List<ChatAuditLogEntity> = chatDao.getAudit(sessionId)

    suspend fun clearStreamingFlags(sessionId: String) = chatDao.clearStreamingFlags(sessionId)

    // ---- TranscriptStore (agent loop) ----

    override suspend fun appendUserMessage(sessionId: String, text: String): String {
        val id = UUID.randomUUID().toString()
        chatDao.insertMessage(
            ChatMessageEntity(
                id = id, sessionId = sessionId, role = "user",
                content = text, createdAt = System.currentTimeMillis()
            )
        )
        touchSession(sessionId)
        return id
    }

    override suspend fun appendAssistantMessage(
        sessionId: String,
        text: String,
        toolUses: List<ToolUseBlock>,
        riskLevels: Map<String, String>
    ): String {
        val id = UUID.randomUUID().toString()
        chatDao.insertMessage(
            ChatMessageEntity(
                id = id, sessionId = sessionId, role = "assistant",
                content = text, createdAt = System.currentTimeMillis()
            )
        )
        touchSession(sessionId)
        return id
    }

    override suspend fun appendToolCallMessage(
        sessionId: String,
        toolUse: ToolUseBlock,
        argsDigest: String,
        riskLevel: RiskLevel,
        approvalState: ApprovalState,
        decisionSource: DecisionSource?
    ): String {
        val id = UUID.randomUUID().toString()
        chatDao.insertMessage(
            ChatMessageEntity(
                id = id, sessionId = sessionId, role = "tool",
                content = "",
                toolName = toolUse.name,
                toolArgsJson = json.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(), toolUse.args
                ),
                toolResultSummary = null,
                riskLevel = riskLevel.name,
                approvalState = approvalState.name,
                decisionSource = decisionSource?.name,
                createdAt = System.currentTimeMillis()
            )
        )
        return id
    }

    override suspend fun updateToolApprovalState(
        messageId: String,
        approvalState: ApprovalState,
        decisionSource: DecisionSource
    ) {
        val message = chatDao.getMessage(messageId) ?: return
        chatDao.updateMessage(
            message.copy(approvalState = approvalState.name, decisionSource = decisionSource.name)
        )
    }

    override suspend fun updateToolResult(messageId: String, summary: String, isError: Boolean) {
        val message = chatDao.getMessage(messageId) ?: return
        chatDao.updateMessage(
            message.copy(
                toolResultSummary = if (isError) "✗ $summary" else summary,
                content = summary
            )
        )
    }

    override suspend fun appendAudit(entry: AuditEntry) {
        chatDao.insertAudit(
            ChatAuditLogEntity(
                sessionId = entry.sessionId,
                messageId = entry.messageId,
                ts = System.currentTimeMillis(),
                mode = entry.mode.name,
                tool = entry.tool,
                argsDigest = entry.argsDigest,
                decisionSource = entry.decisionSource.name,
                approvalState = entry.approvalState.name,
                result = entry.result,
                affectedPaths = entry.affectedPaths.joinToString("\n"),
                bytesWritten = entry.bytesWritten,
                durationMs = entry.durationMs
            )
        )
    }

    override suspend fun buildLlmMessages(sessionId: String): List<LlmMessage> {
        val messages = chatDao.getMessages(sessionId)
        val result = mutableListOf<LlmMessage>()
        for (message in messages) {
            when (message.role) {
                "user" -> result.add(LlmMessage.User(message.content))
                "assistant" -> result.add(LlmMessage.Assistant(message.content, emptyList()))
                "tool" -> Unit // tool results are folded in by the loop as it runs
            }
        }
        return result
    }

    override suspend fun voidPendingApprovals(sessionId: String) {
        chatDao.voidPendingApprovals(sessionId)
    }

    private fun ChatMessageEntity.toView() = ChatMessageView(
        id = id,
        role = role,
        content = content,
        toolName = toolName,
        toolArgsDigest = toolArgsJson, // raw JSON shown collapsed; digest lives in the audit log
        toolResultSummary = toolResultSummary,
        riskLevel = riskLevel,
        approvalState = approvalState?.let { runCatching { ApprovalState.valueOf(it) }.getOrNull() },
        isStreaming = isStreaming
    )

    private suspend fun touchSession(sessionId: String) {
        val session = chatDao.getSession(sessionId) ?: return
        chatDao.updateSession(session.copy(updatedAt = System.currentTimeMillis()))
    }
}
