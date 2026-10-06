package com.orgutil.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single chat message. Tool-related fields are only populated for
 * assistant tool-call messages:
 * - [toolName] / [toolArgsJson]: what the model asked to run;
 * - [toolUseId]: the LLM's tool_use block id, pairing this row with its
 *   assistant turn when the transcript is replayed for the model;
 * - [toolUsesJson]: assistant rows only - the requested tool_use blocks
 *   ([TranscriptReplay] format), so a later run still sees what was called;
 * - [riskLevel]: the tool's risk tier at call time;
 * - [approvalState]: PENDING / APPROVED / DENIED / VOIDED (null for plain
 *   text messages and for AUTO-mode runs, where no approval ever happens);
 * - [decisionSource]: why it ran (user once, session grant, AUTO policy);
 * - [nativeBlocksJson]: assistant rows only - the provider's own content
 *   blocks / output items verbatim when the turn contained HOSTED search
 *   (server_tool_use / web_search_tool_result incl. encrypted_content, or
 *   Responses output items). Replayed exactly on the next turn; null for
 *   ordinary turns.
 */
@Entity(tableName = "chat_message")
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String, // user / assistant / tool
    val content: String,
    val toolName: String? = null,
    val toolArgsJson: String? = null,
    val toolUseId: String? = null,
    val toolUsesJson: String? = null,
    val toolResultSummary: String? = null,
    val riskLevel: String? = null,
    val approvalState: String? = null,
    val decisionSource: String? = null,
    val isStreaming: Boolean = false,
    val createdAt: Long,
    val nativeBlocksJson: String? = null
)
