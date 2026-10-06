package com.orgutil.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One persisted context-compaction summary for a session. [upToCreatedAt]
 * is the createdAt of the last chat_message covered by the summary; the
 * original messages stay in chat_message untouched (history view) while
 * request building uses summary + the messages after [upToCreatedAt].
 */
@Entity(
    tableName = "chat_compaction_summary",
    indices = [Index(value = ["sessionId"])]
)
data class ChatCompactionSummaryEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val summary: String,
    val upToCreatedAt: Long,
    val createdAt: Long
)
