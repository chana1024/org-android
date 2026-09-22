package com.orgutil.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.orgutil.data.database.entity.ChatAuditLogEntity
import com.orgutil.data.database.entity.ChatMessageEntity
import com.orgutil.data.database.entity.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Insert
    suspend fun insertSession(session: ChatSessionEntity)

    @Update
    suspend fun updateSession(session: ChatSessionEntity)

    @Query("SELECT * FROM chat_session ORDER BY updatedAt DESC LIMIT 1")
    fun observeLatestSession(): Flow<ChatSessionEntity?>

    @Query("SELECT * FROM chat_session WHERE id = :id")
    suspend fun getSession(id: String): ChatSessionEntity?

    @Query("DELETE FROM chat_session WHERE id = :id")
    suspend fun deleteSession(id: String)

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
}
