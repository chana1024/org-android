package com.orgutil.domain.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Minimal provider-agnostic LLM contract for the agent loop. */

data class ToolUseBlock(val id: String, val name: String, val args: JsonObject)

data class ToolResultBlock(
    val toolUseId: String,
    val toolName: String,
    val content: String,
    val isError: Boolean
)

/** One provider-reported web search source; never fabricated client-side. */
data class WebSearchSource(val url: String, val title: String? = null)

sealed class LlmMessage {
    /**
     * A user-turn message. [isSummaryHolder] marks the compaction-summary
     * carrier injected at the head of a request; the mid-run trimmer never
     * drops it (it is the only context anchor for the oldest history).
     */
    data class User(val text: String, val isSummaryHolder: Boolean = false) : LlmMessage()
    /**
     * An assistant turn. [nativeBlocks] holds the provider's own content
     * blocks / output items verbatim when the turn involved hosted (server
     * side) search - Anthropic requires those blocks, including
     * encrypted_content, to be replayed exactly or the next request fails
     * with a 400. Null for ordinary turns (text + client tool calls only).
     */
    data class Assistant(
        val text: String,
        val toolUses: List<ToolUseBlock>,
        val nativeBlocks: JsonElement? = null
    ) : LlmMessage()
    data class ToolResults(val results: List<ToolResultBlock>) : LlmMessage()
}

sealed class LlmEvent {
    data class TextDelta(val delta: String) : LlmEvent()
    data class ToolUseArrived(val block: ToolUseBlock) : LlmEvent()

    /**
     * The provider started a hosted (server-side) web search. This is NOT a
     * client [AgentTool] call: it is never executed through the local
     * registry, never approval-gated, and never replayed as a tool_use.
     */
    data class WebSearchStarted(val id: String, val query: String) : LlmEvent()

    /** The hosted search finished; [sources] are provider-reported only. */
    data class WebSearchFinished(
        val id: String,
        val queries: List<String>,
        val sources: List<WebSearchSource>,
        val error: String?
    ) : LlmEvent()

    /**
     * One assistant turn finished; [toolUseCount] drives the loop.
     * [nativeBlocks] carries the provider's verbatim content/output items
     * when the turn contained hosted search blocks (see [LlmMessage.Assistant]).
     */
    data class TurnCompleted(
        val text: String,
        val toolUseCount: Int,
        val nativeBlocks: JsonElement? = null
    ) : LlmEvent()

    data class Failed(val error: Throwable) : LlmEvent()

    /** The router switched to the user-configured fallback profile. */
    data class ProviderSwitched(val profileName: String) : LlmEvent()

    /** Which profile actually serves this request (surfaced in the UI). */
    data class ProviderActive(val profileName: String) : LlmEvent()
}

interface LlmClient {
    val modelName: String
    fun stream(systemPrompt: String, messages: List<LlmMessage>, tools: List<AgentTool>): Flow<LlmEvent>
}
