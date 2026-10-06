package com.orgutil.data.repository

import com.orgutil.data.database.entity.ChatMessageEntity
import com.orgutil.domain.chat.ApprovalState
import com.orgutil.domain.chat.LlmMessage
import com.orgutil.domain.chat.ToolResultBlock
import com.orgutil.domain.chat.ToolUseBlock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Rebuilds the LLM conversation from persisted chat messages. Assistant
 * turns replay their tool_use blocks and every tool row becomes the paired
 * tool_result. The API contract requires each tool_use id to be answered
 * exactly once by the immediately following user message, so any requested
 * call left without a persisted result (denied before execution, stop-button
 * void, crash mid-run) is synthesized as an error result instead of being
 * dropped - otherwise the whole replayed request would be rejected.
 *
 * Assistant turns that contained HOSTED (provider-side) search also carry
 * their provider block array verbatim ([ChatMessageEntity.nativeBlocksJson]
 * -> [LlmMessage.Assistant.nativeBlocks]) so the next request replays it
 * exactly. Hosted-search tool rows themselves are intentionally NOT paired
 * into tool_result messages: their ids never appear in any assistant
 * [toolUsesJson], so they are skipped below like any other stray row.
 *
 * Pre-v6 rows (no [ChatMessageEntity.toolUsesJson] / [ChatMessageEntity.toolUseId])
 * replay as before: assistant text only, tool rows skipped.
 */
internal object TranscriptReplay {

    @Serializable
    private data class PersistedToolUse(val id: String, val name: String, val args: JsonObject)

    private val json = Json { ignoreUnknownKeys = true }

    /** Assistant turns with tool calls persist their blocks; empty turns store null. */
    fun encodeToolUses(toolUses: List<ToolUseBlock>): String? {
        if (toolUses.isEmpty()) return null
        val persisted = toolUses.map { PersistedToolUse(it.id, it.name, it.args) }
        return json.encodeToString(ListSerializer(PersistedToolUse.serializer()), persisted)
    }

    fun build(messages: List<ChatMessageEntity>): List<LlmMessage> {
        val result = mutableListOf<LlmMessage>()
        var pendingUses: List<ToolUseBlock> = emptyList()
        var resultsByUseId = mutableMapOf<String, ToolResultBlock>()

        fun flushPendingExchange() {
            if (pendingUses.isEmpty()) return
            result.add(
                LlmMessage.ToolResults(
                    pendingUses.map { use ->
                        resultsByUseId.remove(use.id) ?: interruptedResult(use)
                    }
                )
            )
            // Results whose tool_use is not in the current exchange cannot be
            // sent (the API rejects unknown tool_use ids); drop them.
            resultsByUseId = mutableMapOf()
            pendingUses = emptyList()
        }

        for (message in messages) {
            when (message.role) {
                "user" -> {
                    flushPendingExchange()
                    result.add(LlmMessage.User(ChatMessageContextCodec.forModel(message.content)))
                }
                "assistant" -> {
                    flushPendingExchange()
                    val uses = decodeToolUses(message.toolUsesJson)
                    result.add(
                        LlmMessage.Assistant(message.content, uses, decodeNativeBlocks(message.nativeBlocksJson))
                    )
                    pendingUses = uses
                }
                "tool" -> {
                    val useId = message.toolUseId
                    if (useId != null && pendingUses.any { it.id == useId }) {
                        resultsByUseId[useId] = message.toToolResult()
                    }
                }
            }
        }
        flushPendingExchange()
        return result
    }

    private fun decodeToolUses(toolUsesJson: String?): List<ToolUseBlock> {
        if (toolUsesJson == null) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(PersistedToolUse.serializer()), toolUsesJson)
                .map { ToolUseBlock(id = it.id, name = it.name, args = it.args) }
        }.getOrDefault(emptyList())
    }

    /** Provider blocks replay verbatim; invalid stored JSON degrades to null. */
    private fun decodeNativeBlocks(nativeBlocksJson: String?): JsonElement? {
        if (nativeBlocksJson == null) return null
        return runCatching { json.parseToJsonElement(nativeBlocksJson) }.getOrNull()
    }

    /** Executed calls replay their persisted result; the rest synthesize what the model would have seen. */
    private fun ChatMessageEntity.toToolResult(): ToolResultBlock {
        if (content.isNotBlank()) {
            return ToolResultBlock(
                toolUseId = toolUseId!!,
                toolName = toolName ?: "",
                content = content,
                isError = toolResultSummary?.startsWith("✗ ") == true
            )
        }
        val (text, isError) = when (runCatching { approvalState?.let { ApprovalState.valueOf(it) } }.getOrNull()) {
            ApprovalState.DENIED -> "User declined this action." to true
            ApprovalState.VOIDED -> "Cancelled before execution." to true
            ApprovalState.PENDING -> "Interrupted: the approval was never resolved." to true
            // FM-R3: the call was APPROVED and executing when the process
            // died - the write/delete/git push may or may not have landed.
            // State that honestly; never fabricate an outcome.
            ApprovalState.APPROVED ->
                "Execution state unknown: this tool call was in flight when the run ended; " +
                    "it may or may not have completed. Check the affected file/git state before retrying." to true
            else -> "(interrupted: no result was recorded)" to true
        }
        return ToolResultBlock(toolUseId = toolUseId!!, toolName = toolName ?: "", content = text, isError = isError)
    }

    /** A requested call whose row never appeared (crash between request and tool-call insert). */
    private fun interruptedResult(use: ToolUseBlock): ToolResultBlock =
        ToolResultBlock(
            toolUseId = use.id,
            toolName = use.name,
            content = "(interrupted: no result was recorded)",
            isError = true
        )
}
