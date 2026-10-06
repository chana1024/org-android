package com.orgutil.data.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.orgutil.data.database.entity.ChatAuditLogEntity
import com.orgutil.data.database.entity.ChatCompactionSummaryEntity
import com.orgutil.data.database.entity.ChatMessageEntity
import com.orgutil.data.database.entity.ChatSessionEntity
import com.orgutil.data.database.entity.LlmProviderProfileEntity
import kotlinx.coroutines.flow.Flow

/**
 * Session-list row for the switcher UI: the session plus read-only derived
 * columns (message count, last user/assistant text as preview). Sorting
 * ALWAYS goes through the deterministic rule in [ORDER_SESSIONS_BY_ACTIVITY]:
 * most-recent-activity first with createdAt/id tie breakers, so equal
 * timestamps (batch-stamped runs, same-minute activity) can never make the
 * list order flip between openings.
 */
const val ORDER_SESSIONS_BY_ACTIVITY = "ORDER BY updatedAt DESC, createdAt DESC, id DESC"

data class SessionOverviewRow(
    @Embedded val session: ChatSessionEntity,
    val messageCount: Int,
    val preview: String?
)

@Dao
interface ChatDao {

    @Insert
    suspend fun insertSession(session: ChatSessionEntity)

    @Update
    suspend fun updateSession(session: ChatSessionEntity)

    @Query("SELECT * FROM chat_session $ORDER_SESSIONS_BY_ACTIVITY LIMIT 1")
    fun observeLatestSession(): Flow<ChatSessionEntity?>

    /**
     * Live session list for the switcher. Preview comes from the persisted
     * transcript only (latest user/assistant row; tool rows carry no text),
     * truncated in SQL so a huge reply never crosses the cursor boundary.
     */
    @Query(
        """
        SELECT s.*,
            (SELECT COUNT(*) FROM chat_message m WHERE m.sessionId = s.id) AS messageCount,
            (SELECT substr(m.content, 1, 200) FROM chat_message m
             WHERE m.sessionId = s.id AND m.role != 'tool' AND length(trim(m.content)) > 0
             ORDER BY m.createdAt DESC, m.id DESC LIMIT 1) AS preview
        FROM chat_session s
        $ORDER_SESSIONS_BY_ACTIVITY
        """
    )
    fun observeSessionOverviews(): Flow<List<SessionOverviewRow>>

    @Query("SELECT * FROM chat_session $ORDER_SESSIONS_BY_ACTIVITY")
    suspend fun getAllSessions(): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_session WHERE id = :id")
    suspend fun getSession(id: String): ChatSessionEntity?

    @Query("UPDATE chat_session SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun renameSession(id: String, title: String, now: Long)

    /**
     * Deletes a session with ALL of its data. There is no FK between the
     * chat tables (kept loose by design), so the children are removed
     * explicitly in one transaction; orphans would otherwise reappear in
     * transcripts, audit and compaction reads.
     */
    @Transaction
    suspend fun deleteSessionCascade(id: String) {
        deleteSessionMessages(id)
        deleteSessionAudit(id)
        deleteSessionCompaction(id)
        deleteSession(id)
    }

    /**
     * Inserts a forked session with its copied transcript and compaction
     * summaries atomically: the fork either appears complete or not at all —
     * a half-copied transcript must never be observable or resumable.
     */
    @Transaction
    suspend fun insertForkSession(
        session: ChatSessionEntity,
        messages: List<ChatMessageEntity>,
        compactionSummaries: List<ChatCompactionSummaryEntity>
    ) {
        insertSession(session)
        messages.forEach { insertMessage(it) }
        compactionSummaries.forEach { insertCompactionSummary(it) }
    }

    @Query("DELETE FROM chat_session WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("DELETE FROM chat_message WHERE sessionId = :sessionId")
    suspend fun deleteSessionMessages(sessionId: String)

    @Query("DELETE FROM chat_audit_log WHERE sessionId = :sessionId")
    suspend fun deleteSessionAudit(sessionId: String)

    @Query("DELETE FROM chat_compaction_summary WHERE sessionId = :sessionId")
    suspend fun deleteSessionCompaction(sessionId: String)

    @Query("UPDATE chat_session SET runStatus = :status, lastPrompt = :lastPrompt, updatedAt = :now WHERE id = :id")
    suspend fun updateRunState(id: String, status: String, lastPrompt: String?, now: Long)

    @Query("UPDATE chat_session SET runStatus = :status, updatedAt = :now WHERE id = :id")
    suspend fun updateRunStatus(id: String, status: String, now: Long)

    @Query("UPDATE chat_session SET profileId = :profileId, updatedAt = :now WHERE id = :id")
    suspend fun updateSessionProfile(id: String, profileId: String?, now: Long)

    @Insert
    suspend fun insertMessage(message: ChatMessageEntity)

    @Update
    suspend fun updateMessage(message: ChatMessageEntity)

    @Query("SELECT * FROM chat_message WHERE id = :id")
    suspend fun getMessage(id: String): ChatMessageEntity?

    @Query("SELECT * FROM chat_message WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(sessionId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_message WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    suspend fun getMessages(sessionId: String): List<ChatMessageEntity>

    /** Clears stale streaming flags left by a killed process. */
    @Query("UPDATE chat_message SET isStreaming = 0 WHERE sessionId = :sessionId")
    suspend fun clearStreamingFlags(sessionId: String)

    /** Voids every pending tool approval of a session (stop button semantics). */
    @Query(
        "UPDATE chat_message SET approvalState = 'VOIDED' " +
            "WHERE sessionId = :sessionId AND approvalState = 'PENDING'"
    )
    suspend fun voidPendingApprovals(sessionId: String)

    @Insert
    suspend fun insertAudit(entry: ChatAuditLogEntity)

    @Query("SELECT * FROM chat_audit_log WHERE sessionId = :sessionId ORDER BY ts ASC, id ASC")
    suspend fun getAudit(sessionId: String): List<ChatAuditLogEntity>

    @Query("SELECT COUNT(*) FROM chat_audit_log WHERE sessionId = :sessionId AND mode = 'AUTO' AND result != 'DENIED'")
    suspend fun countAutoExecutedActions(sessionId: String): Int

    // ---- compaction summaries ----

    @Insert
    suspend fun insertCompactionSummary(summary: ChatCompactionSummaryEntity)

    @Query("SELECT * FROM chat_compaction_summary WHERE sessionId = :sessionId ORDER BY upToCreatedAt DESC LIMIT 1")
    suspend fun latestCompactionSummary(sessionId: String): ChatCompactionSummaryEntity?

    // ---- provider profiles ----

    @Insert
    suspend fun insertProfile(profile: LlmProviderProfileEntity)

    @Update
    suspend fun updateProfile(profile: LlmProviderProfileEntity)

    @Query("SELECT * FROM llm_provider_profile ORDER BY createdAt ASC")
    fun observeProfiles(): Flow<List<LlmProviderProfileEntity>>

    @Query("SELECT * FROM llm_provider_profile ORDER BY createdAt ASC")
    suspend fun getProfiles(): List<LlmProviderProfileEntity>

    @Query("SELECT * FROM llm_provider_profile WHERE id = :id")
    suspend fun getProfile(id: String): LlmProviderProfileEntity?

    @Query("SELECT * FROM llm_provider_profile WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultProfile(): LlmProviderProfileEntity?

    @Query("UPDATE llm_provider_profile SET isDefault = 0 WHERE isDefault = 1")
    suspend fun clearDefaultFlag()

    @Query("DELETE FROM llm_provider_profile WHERE id = :id")
    suspend fun deleteProfile(id: String)

    @Query("UPDATE chat_session SET profileId = NULL WHERE profileId = :profileId")
    suspend fun detachSessionsFromProfile(profileId: String)

    @Transaction
    suspend fun setDefaultProfile(profile: LlmProviderProfileEntity) {
        clearDefaultFlag()
        updateProfile(profile.copy(isDefault = true, updatedAt = System.currentTimeMillis()))
    }
}
