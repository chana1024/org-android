package com.orgutil.domain.chat

import kotlinx.serialization.json.JsonObject

/**
 * A tool invocation requested by the model. Arguments are raw JSON - the
 * tool is responsible for validating them.
 */
data class ToolCallRequest(
    val id: String,
    val toolName: String,
    val args: JsonObject
)

/** Structured tool outcome; [summaryForModel] is what the LLM sees. */
sealed class ToolResult {
    abstract val summaryForModel: String
    abstract val affectedPaths: List<String>
    abstract val bytesWritten: Long

    data class Ok(
        override val summaryForModel: String,
        override val affectedPaths: List<String> = emptyList(),
        override val bytesWritten: Long = 0
    ) : ToolResult()

    data class Error(
        override val summaryForModel: String,
        override val affectedPaths: List<String> = emptyList(),
        override val bytesWritten: Long = 0
    ) : ToolResult()

    /** The user (or stop button) refused the call; fed back to the model as data. */
    data class Denied(
        override val summaryForModel: String = "User declined this action.",
        override val affectedPaths: List<String> = emptyList(),
        override val bytesWritten: Long = 0
    ) : ToolResult()
}

/**
 * A capability the agent may call. Registration IS the capability boundary:
 * whatever is not in the registry simply does not exist for the agent
 * (bash, out-of-tree paths, etc. are deliberately not registered).
 */
interface AgentTool {
    val name: String
    val description: String
    /** JSON Schema (draft-ish) of the arguments, injected into the system prompt. */
    val parametersSchema: JsonObject
    val policy: ToolPolicy

    /** Human-readable digest of the arguments for approval cards and audit. */
    fun describeArgs(args: JsonObject): String

    suspend fun execute(args: JsonObject): ToolResult
}

/** Thrown by tools on invalid arguments (bad path, missing field, ...). */
class ToolArgumentException(message: String) : IllegalArgumentException(message)

/** Lookup over the registered tools; the registry IS the capability boundary. */
interface AgentToolCatalog {
    val tools: List<AgentTool>
    fun byName(name: String): AgentTool?
}
