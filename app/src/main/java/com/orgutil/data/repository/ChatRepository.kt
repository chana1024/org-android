package com.orgutil.data.repository

import com.orgutil.data.database.dao.ChatDao
import com.orgutil.data.database.dao.SessionOverviewRow
import com.orgutil.data.database.entity.ChatAuditLogEntity
import com.orgutil.data.database.entity.ChatCompactionSummaryEntity
import com.orgutil.data.database.entity.ChatMessageEntity
import com.orgutil.data.database.entity.ChatRunStatus
import com.orgutil.data.database.entity.ChatSessionEntity
import com.orgutil.data.database.entity.LlmProviderProfileEntity
import com.orgutil.data.datasource.ActiveSessionStore
import com.orgutil.domain.chat.ActiveSessionResolver
import com.orgutil.domain.chat.AgentMode
import com.orgutil.domain.chat.ApprovalState
import com.orgutil.domain.chat.AuditEntry
import com.orgutil.domain.chat.ChatMessageView
import com.orgutil.domain.chat.DecisionSource
import com.orgutil.domain.chat.LlmMessage
import com.orgutil.domain.chat.RiskLevel
import com.orgutil.domain.chat.RunStateTracker
import com.orgutil.domain.chat.ToolUseBlock
import com.orgutil.domain.chat.TranscriptStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed transcript + audit trail. Also owns session lifecycle
 * (create/list/restore/switch/rename/delete, run-state transitions for
 * restart recovery) for the UI, and implements [RunStateTracker] +
 * [CompactionSummaryStore] for the agent loop.
 */
@Singleton
class ChatRepository @Inject constructor(
    private val chatDao: ChatDao,
    private val activeSessionStore: ActiveSessionStore
) : TranscriptStore, RunStateTracker, CompactionSummaryStore {

    private val json = Json { ignoreUnknownKeys = true }

    /** Serializes create-vs-restore so restart racing never spawns two sessions. */
    private val sessionMutex = Mutex()

    /**
     * Inserts inside one run can land in the same millisecond; transcript
     * order (and thus tool_use/tool_result pairing on replay) must never
     * depend on the random-UUID tiebreak, so createdAt stays strictly
     * increasing within this process.
     */
    private var lastMessageTs = 0L

    @Synchronized
    private fun nextMessageTs(): Long {
        val now = System.currentTimeMillis()
        lastMessageTs = maxOf(now, lastMessageTs + 1)
        return lastMessageTs
    }

    // ---- session management (UI) ----

    /**
     * Restores the session the user left: persisted active id when it still
     * exists, else the most recently updated one, else a fresh session
     * (created exactly once thanks to [sessionMutex]).
     */
    suspend fun restoreActiveSession(mode: AgentMode, autoArmed: Boolean): ChatSessionEntity =
        sessionMutex.withLock {
            val sessions = chatDao.getAllSessions()
            val resolved = ActiveSessionResolver.resolve(
                savedId = activeSessionStore.get(),
                orderedIds = sessions.map { it.id }
            )
            val session = sessions.find { it.id == resolved }
                ?: newSessionLocked(mode, autoArmed, title = "Chat")
            activeSessionStore.set(session.id)
            session
        }

    suspend fun createSession(mode: AgentMode, autoArmed: Boolean, title: String = "新会话"): ChatSessionEntity =
        sessionMutex.withLock {
            newSessionLocked(mode, autoArmed, title).also { activeSessionStore.set(it.id) }
        }

    /**
     * Forks [sourceId] into an independent new session: the persisted
     * transcript is copied verbatim under fresh message ids (the LLM's
     * tool_use ids and their tool rows keep their values, so replay pairing
     * holds inside the fork), together with the profile selection, mode and
     * the latest compaction summary so request building behaves the same.
     * The source session, its audit history and all other data are never
     * touched.
     *
     * Refuses (IllegalStateException, message fit for the UI) unless the
     * source is safely settled: no live run, no unresolved approval, and at
     * least one message. The fork itself lands atomically
     * ([ChatDao.insertForkSession]) and becomes the active session.
     */
    suspend fun forkSession(sourceId: String): ChatSessionEntity =
        sessionMutex.withLock {
            val source = chatDao.getSession(sourceId)
                ?: throw IllegalStateException("原会话不存在，无法复制。")
            if (source.runStatus == ChatRunStatus.RUNNING.name ||
                source.runStatus == ChatRunStatus.AWAITING_APPROVAL.name
            ) {
                throw IllegalStateException("会话仍在运行或等待批准，无法复制：请先按停止，结束后再使用 /fork。")
            }
            val messages = chatDao.getMessages(sourceId)
            if (messages.isEmpty()) {
                throw IllegalStateException("当前会话是空的，没有可复制的上下文。")
            }
            if (messages.any { it.approvalState == ApprovalState.PENDING.name }) {
                throw IllegalStateException("会话还有未处理的批准请求，无法复制：请先停止当前运行再使用 /fork。")
            }
            val now = System.currentTimeMillis()
            val forkId = UUID.randomUUID().toString()
            chatDao.insertForkSession(
                session = source.copy(
                    id = forkId,
                    title = "${source.title} · 副本",
                    createdAt = now,
                    updatedAt = now,
                    // A fork is never mid-run; the copied prompt marker is
                    // meaningless in the new session.
                    runStatus = ChatRunStatus.IDLE.name,
                    lastPrompt = null
                ),
                messages = messages.map { message ->
                    message.copy(
                        id = UUID.randomUUID().toString(),
                        sessionId = forkId,
                        // Copied rows are persisted snapshots, never streams.
                        isStreaming = false
                    )
                },
                compactionSummaries = chatDao.latestCompactionSummary(sourceId)?.let { summary ->
                    listOf(summary.copy(id = UUID.randomUUID().toString(), sessionId = forkId))
                } ?: emptyList()
            )
            val fork = chatDao.getSession(forkId) ?: error("Fork $forkId vanished right after insert")
            activeSessionStore.set(forkId)
            fork
        }

    private suspend fun newSessionLocked(
        mode: AgentMode,
        autoArmed: Boolean,
        title: String
    ): ChatSessionEntity {
        val now = System.currentTimeMillis()
        val session = ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            mode = mode.name,
            createdAt = now,
            autoArmedAt = if (mode == AgentMode.AUTO && autoArmed) now else null,
            updatedAt = now
        )
        chatDao.insertSession(session)
        return session
    }

    fun observeSessions(): Flow<List<SessionOverviewRow>> = chatDao.observeSessionOverviews()

    suspend fun setActiveSession(sessionId: String): ChatSessionEntity? {
        val session = chatDao.getSession(sessionId) ?: return null
        activeSessionStore.set(sessionId)
        return session
    }

    suspend fun renameSession(sessionId: String, title: String) {
        val trimmed = title.trim().takeIf { it.isNotEmpty() } ?: return
        chatDao.renameSession(sessionId, trimmed, System.currentTimeMillis())
    }

    /**
     * Deletes the session with all messages/audit/compaction rows and
     * returns the session that becomes active afterwards (null when the
     * list is now empty - caller creates a fresh one).
     */
    suspend fun deleteSession(sessionId: String): ChatSessionEntity? {
        chatDao.deleteSessionCascade(sessionId)
        activeSessionStore.clearIf(sessionId)
        val remaining = chatDao.getAllSessions()
        val nextId = ActiveSessionResolver.resolve(activeSessionStore.get(), remaining.map { it.id })
        val next = nextId?.let { chatDao.getSession(it) }
        if (next != null) activeSessionStore.set(next.id) else activeSessionStore.clear()
        return next
    }

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

    // ---- run state (restart recovery) ----

    override suspend fun onRunStarted(sessionId: String, prompt: String?) {
        val existing = chatDao.getSession(sessionId) ?: return
        // Keep the original prompt while a run moves through approval phases
        // (prompt == null means "phase change", not "clear").
        if (prompt != null) {
            chatDao.updateRunState(sessionId, ChatRunStatus.RUNNING.name, prompt, System.currentTimeMillis())
        } else if (existing.runStatus != ChatRunStatus.RUNNING.name) {
            chatDao.updateRunState(sessionId, ChatRunStatus.RUNNING.name, existing.lastPrompt, System.currentTimeMillis())
        }
    }

    override suspend fun onAwaitingApproval(sessionId: String) =
        chatDao.updateRunStatus(sessionId, ChatRunStatus.AWAITING_APPROVAL.name, System.currentTimeMillis())

    override suspend fun onRunSettled(sessionId: String) = markRunIdle(sessionId)

    suspend fun markRunRunning(sessionId: String, prompt: String) =
        chatDao.updateRunState(sessionId, ChatRunStatus.RUNNING.name, prompt, System.currentTimeMillis())

    suspend fun markRunAwaitingApproval(sessionId: String) =
        chatDao.updateRunStatus(sessionId, ChatRunStatus.AWAITING_APPROVAL.name, System.currentTimeMillis())

    /**
     * Settles a run back to IDLE. Skips the write entirely when the session
     * is already IDLE: stop() fires this on every session switch/create, and
     * stamping updatedAt on a session the user merely LEFT would reorder the
     * session list between openings. A genuine RUNNING/AWAITING_APPROVAL
     * settle (stop button mid-run, restart recovery discard) still writes.
     */
    suspend fun markRunIdle(sessionId: String) {
        val session = chatDao.getSession(sessionId) ?: return
        if (session.runStatus == ChatRunStatus.IDLE.name) return
        chatDao.updateRunState(sessionId, ChatRunStatus.IDLE.name, null, System.currentTimeMillis())
    }

    /**
     * Startup pass: runs left RUNNING/AWAITING_APPROVAL by a dead process
     * become INTERRUPTED and their never-answered approval cards are voided
     * (FM-R4: a suspended approval must not deadlock the resumed request).
     */
    suspend fun markInterruptedRuns(): List<ChatSessionEntity> {
        val interrupted = chatDao.getAllSessions().filter {
            it.runStatus == ChatRunStatus.RUNNING.name || it.runStatus == ChatRunStatus.AWAITING_APPROVAL.name
        }
        val now = System.currentTimeMillis()
        interrupted.forEach {
            chatDao.voidPendingApprovals(it.id)
            chatDao.updateRunStatus(it.id, ChatRunStatus.INTERRUPTED.name, now)
        }
        return interrupted
    }

    // ---- compaction summaries ----

    override suspend fun latest(sessionId: String): Pair<String, Long>? =
        chatDao.latestCompactionSummary(sessionId)?.let { it.summary to it.upToCreatedAt }

    override suspend fun save(sessionId: String, summary: String, upToCreatedAt: Long) {
        saveCompactionSummary(sessionId, summary, upToCreatedAt)
    }

    suspend fun saveCompactionSummary(sessionId: String, summary: String, upToCreatedAt: Long) {
        chatDao.insertCompactionSummary(
            ChatCompactionSummaryEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                summary = summary,
                upToCreatedAt = upToCreatedAt,
                createdAt = System.currentTimeMillis()
            )
        )
        touchSession(sessionId)
    }

    suspend fun latestCompactionSummary(sessionId: String): ChatCompactionSummaryEntity? =
        chatDao.latestCompactionSummary(sessionId)

    suspend fun getMessagesRaw(sessionId: String): List<ChatMessageEntity> = chatDao.getMessages(sessionId)

    // ---- provider profiles ----

    fun observeProfiles(): Flow<List<LlmProviderProfileEntity>> = chatDao.observeProfiles()

    suspend fun insertProfile(profile: LlmProviderProfileEntity) = chatDao.insertProfile(profile)

    suspend fun updateProfile(profile: LlmProviderProfileEntity) = chatDao.updateProfile(profile)

    suspend fun setDefaultProfile(profile: LlmProviderProfileEntity) = chatDao.setDefaultProfile(profile)

    suspend fun setSessionProfile(sessionId: String, profileId: String?) =
        chatDao.updateSessionProfile(sessionId, profileId, System.currentTimeMillis())

    suspend fun profileIdOf(sessionId: String): String? = chatDao.getSession(sessionId)?.profileId

    /** Deletes the profile and clears every session override pointing at it. */
    suspend fun deleteProfileCascade(profileId: String) {
        chatDao.detachSessionsFromProfile(profileId)
        chatDao.deleteProfile(profileId)
    }

    // ---- transcript observation ----

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
                content = text, createdAt = nextMessageTs()
            )
        )
        touchSession(sessionId)
        return id
    }

    override suspend fun appendAssistantMessage(
        sessionId: String,
        text: String,
        toolUses: List<ToolUseBlock>,
        riskLevels: Map<String, String>,
        nativeBlocksJson: String?
    ): String {
        val id = UUID.randomUUID().toString()
        chatDao.insertMessage(
            ChatMessageEntity(
                id = id, sessionId = sessionId, role = "assistant",
                content = text,
                toolUsesJson = TranscriptReplay.encodeToolUses(toolUses),
                createdAt = nextMessageTs(),
                nativeBlocksJson = nativeBlocksJson
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
                toolUseId = toolUse.id,
                toolArgsJson = json.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(), toolUse.args
                ),
                toolResultSummary = null,
                riskLevel = riskLevel.name,
                approvalState = approvalState.name,
                decisionSource = decisionSource?.name,
                createdAt = nextMessageTs()
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

    override suspend fun buildLlmMessages(sessionId: String): List<LlmMessage> =
        TranscriptReplay.build(chatDao.getMessages(sessionId))

    override suspend fun voidPendingApprovals(sessionId: String) {
        chatDao.voidPendingApprovals(sessionId)
    }

    private fun ChatMessageEntity.toView(): ChatMessageView {
        val decoded = ChatMessageContextCodec.decode(content)
        return ChatMessageView(
            id = id,
            role = role,
            content = decoded.prompt,
            toolName = toolName,
            toolArgsDigest = toolArgsJson, // raw JSON shown collapsed; digest lives in the audit log
            toolResultSummary = toolResultSummary,
            riskLevel = riskLevel,
            approvalState = approvalState?.let { runCatching { ApprovalState.valueOf(it) }.getOrNull() },
            isStreaming = isStreaming,
            agendaReferences = decoded.references,
            skillId = decoded.skill?.skillId
        )
    }

    private suspend fun touchSession(sessionId: String) {
        val session = chatDao.getSession(sessionId) ?: return
        chatDao.updateSession(session.copy(updatedAt = System.currentTimeMillis()))
    }
}
