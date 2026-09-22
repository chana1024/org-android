package com.orgutil.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Append-only audit trail of every agent tool call. Written for both
 * APPROVAL and AUTO modes; in AUTO mode every row carries
 * decisionSource=AUTO_POLICY, so the full agent activity is replayable.
 */
@Entity(tableName = "chat_audit_log")
data class ChatAuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val messageId: String,
    val ts: Long,
    val mode: String,
    val tool: String,
    val argsDigest: String,
    val decisionSource: String, // USER_ONCE / USER_SESSION / AUTO_POLICY
    val approvalState: String,  // APPROVED / DENIED / VOIDED
    val result: String,         // OK / ERROR / DENIED
    val affectedPaths: String,  // newline-separated relative paths
    val bytesWritten: Long,
    val durationMs: Long
)
