package com.orgutil.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One agent chat session. [mode] is the mode active when the session was
 * created; the live mode is tracked per message via [com.orgutil.domain.chat
 * decision fields][ChatMessageEntity] so the audit trail shows which policy
 * governed each tool call even after a mid-session switch.
 */
@Entity(tableName = "chat_session")
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val mode: String, // AgentMode.name
    val createdAt: Long,
    val autoArmedAt: Long? = null,
    val updatedAt: Long = 0
)
