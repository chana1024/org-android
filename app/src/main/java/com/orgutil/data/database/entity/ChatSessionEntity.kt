package com.orgutil.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Run lifecycle persisted per session so a killed run is detectable/resumable. */
enum class ChatRunStatus { IDLE, RUNNING, AWAITING_APPROVAL, INTERRUPTED }

/**
 * One agent chat session. [mode] is the mode active when the session was
 * created; the live mode is tracked per message via [com.orgutil.domain.chat
 * decision fields][ChatMessageEntity] so the audit trail shows which policy
 * governed each tool call even after a mid-session switch.
 *
 * [profileId] selects the provider profile (NULL = default profile);
 * [runStatus]/[lastPrompt] record the in-flight run for restart recovery.
 */
@Entity(tableName = "chat_session")
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val mode: String, // AgentMode.name
    val createdAt: Long,
    val autoArmedAt: Long? = null,
    val updatedAt: Long = 0,
    val profileId: String? = null,
    @ColumnInfo(defaultValue = "IDLE") val runStatus: String = ChatRunStatus.IDLE.name,
    val lastPrompt: String? = null
)