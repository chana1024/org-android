package com.orgutil.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single chat message. Tool-related fields are only populated for
 * assistant tool-call messages:
 * - [toolName] / [toolArgsJson]: what the model asked to run;
 * - [riskLevel]: the tool's risk tier at call time;
 * - [approvalState]: PENDING / APPROVED / DENIED / VOIDED (null for plain
 *   text messages and for AUTO-mode runs, where no approval ever happens);
 * - [decisionSource]: why it ran (user once, session grant, AUTO policy).
 */
@Entity(tableName = "chat_message")
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String, // user / assistant / tool
    val content: String,
    val toolName: String? = null,
    val toolArgsJson: String? = null,
    val toolResultSummary: String? = null,
    val riskLevel: String? = null,
    val approvalState: String? = null,
    val decisionSource: String? = null,
    val isStreaming: Boolean = false,
    val createdAt: Long
)
