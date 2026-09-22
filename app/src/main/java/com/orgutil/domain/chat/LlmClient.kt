package com.orgutil.domain.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/** Minimal provider-agnostic LLM contract for the agent loop. */

data class ToolUseBlock(val id: String, val name: String, val args: JsonObject)

data class ToolResultBlock(
    val toolUseId: String,
    val toolName: String,
    val content: String,
    val isError: Boolean
)

sealed class LlmMessage {
    data class User(val text: String) : LlmMessage()
    data class Assistant(val text: String, val toolUses: List<ToolUseBlock>) : LlmMessage()
    data class ToolResults(val results: List<ToolResultBlock>) : LlmMessage()
}

sealed class LlmEvent {
    data class TextDelta(val delta: String) : LlmEvent()
    data class ToolUseArrived(val block: ToolUseBlock) : LlmEvent()

    /** One assistant turn finished; [toolUseCount] drives the loop. */
    data class TurnCompleted(val text: String, val toolUseCount: Int) : LlmEvent()
    data class Failed(val error: Throwable) : LlmEvent()
}

interface LlmClient {
    val modelName: String
    fun stream(systemPrompt: String, messages: List<LlmMessage>, tools: List<AgentTool>): Flow<LlmEvent>
}
