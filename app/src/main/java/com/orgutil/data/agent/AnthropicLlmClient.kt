package com.orgutil.data.agent

import com.orgutil.domain.chat.AgentTool
import com.orgutil.domain.chat.LlmClient
import com.orgutil.domain.chat.LlmEvent
import com.orgutil.domain.chat.LlmMessage
import com.orgutil.domain.chat.ToolUseBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal Anthropic Messages API streaming client (SSE). Supports exactly
 * what the agent loop needs: system prompt, tool declarations, streamed
 * text deltas and tool_use blocks. No retries, no fallbacks - failures
 * surface as [LlmEvent.Failed].
 */
@Singleton
class AnthropicLlmClient @Inject constructor() : LlmClient {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    var apiKey: String = ""

    @Volatile
    override var modelName: String = "claude-sonnet-5"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // SSE: read indefinitely, cancel via coroutine
        .build()

    override fun stream(
        systemPrompt: String,
        messages: List<LlmMessage>,
        tools: List<AgentTool>
    ): Flow<LlmEvent> = callbackFlow {
        val body = buildRequestBody(systemPrompt, messages, tools)
        val request = Request.Builder()
            .url("$API_BASE/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val job = launch(Dispatchers.IO) {
            var response: Response? = null
            try {
                response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty().take(500)
                    trySend(LlmEvent.Failed(IOException("HTTP ${response.code}: $errorBody")))
                    return@launch
                }
                val reader: BufferedReader = response.body?.charStream()?.buffered()
                    ?: run {
                        trySend(LlmEvent.Failed(IOException("Empty response body")))
                        return@launch
                    }

                val text = StringBuilder()
                val toolUses = mutableListOf<ToolUseAccumulator>()
                var currentToolIndex = -1

                reader.useLines { lines ->
                    for (line in lines) {
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload.isEmpty() || payload == "[DONE]") continue
                        val event = json.parseToJsonElement(payload).jsonObject
                        when (event["type"]?.jsonPrimitive?.content) {
                            "content_block_start" -> {
                                val block = event["content_block"]?.jsonObject
                                if (block?.get("type")?.jsonPrimitive?.content == "tool_use") {
                                    currentToolIndex = toolUses.size
                                    toolUses.add(
                                        ToolUseAccumulator(
                                            id = block["id"]?.jsonPrimitive?.content ?: "",
                                            name = block["name"]?.jsonPrimitive?.content ?: ""
                                        )
                                    )
                                }
                            }
                            "content_block_delta" -> {
                                val delta = event["delta"]?.jsonObject
                                when (delta?.get("type")?.jsonPrimitive?.content) {
                                    "text_delta" -> {
                                        val t = delta["text"]?.jsonPrimitive?.content ?: ""
                                        text.append(t)
                                        if (t.isNotEmpty()) trySend(LlmEvent.TextDelta(t))
                                    }
                                    "input_json_delta" -> {
                                        if (currentToolIndex in toolUses.indices) {
                                            toolUses[currentToolIndex].partialJson.append(
                                                delta["partial_json"]?.jsonPrimitive?.content ?: ""
                                            )
                                        }
                                    }
                                }
                            }
                            "message_stop" -> return@useLines
                        }
                    }
                }

                toolUses.forEach { accumulator ->
                    val args = if (accumulator.partialJson.isBlank()) JsonObject(emptyMap())
                    else runCatching { json.parseToJsonElement(accumulator.partialJson.toString()).jsonObject }
                        .getOrElse { JsonObject(emptyMap()) }
                    val block = ToolUseBlock(accumulator.id, accumulator.name, args)
                    trySend(LlmEvent.ToolUseArrived(block))
                }
                trySend(LlmEvent.TurnCompleted(text.toString(), toolUses.size))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                trySend(LlmEvent.Failed(e))
            } finally {
                response?.close()
                close()
            }
        }

        awaitClose {
            job.cancel()
            // OkHttp readTimeout is 0 (SSE); cancelling the coroutine closes
            // via the response close in finally when the read aborts.
        }
    }

    private fun buildRequestBody(
        systemPrompt: String,
        messages: List<LlmMessage>,
        tools: List<AgentTool>
    ): String {
        val sanitized = messages.filterNot { message ->
            message is LlmMessage.Assistant && message.text.isEmpty() && message.toolUses.isEmpty()
        }
        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("model", modelName)
                put("max_tokens", MAX_TOKENS)
                put("stream", true)
                put("system", systemPrompt)
                put("messages", buildJsonArray {
                    sanitized.forEach { message ->
                        add(
                            buildJsonObject {
                                when (message) {
                                    is LlmMessage.User -> {
                                        put("role", "user")
                                        put("content", buildJsonArray {
                                            add(buildJsonObject {
                                                put("type", "text")
                                                put("text", message.text)
                                            })
                                        })
                                    }
                                    is LlmMessage.Assistant -> {
                                        put("role", "assistant")
                                        put("content", buildJsonArray {
                                            if (message.text.isNotEmpty()) {
                                                add(buildJsonObject {
                                                    put("type", "text")
                                                    put("text", message.text)
                                                })
                                            }
                                            message.toolUses.forEach { toolUse ->
                                                add(buildJsonObject {
                                                    put("type", "tool_use")
                                                    put("id", toolUse.id)
                                                    put("name", toolUse.name)
                                                    put("input", toolUse.args)
                                                })
                                            }
                                        })
                                    }
                                    is LlmMessage.ToolResults -> {
                                        put("role", "user")
                                        put("content", buildJsonArray {
                                            message.results.forEach { result ->
                                                add(buildJsonObject {
                                                    put("type", "tool_result")
                                                    put("tool_use_id", result.toolUseId)
                                                    put("is_error", result.isError)
                                                    put("content", result.content)
                                                })
                                            }
                                        })
                                    }
                                }
                            }
                        )
                    }
                })
                put("tools", buildJsonArray {
                    tools.forEach { tool ->
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", mergeSchemaWithDefaults(tool.parametersSchema))
                        })
                    }
                })
            }
        )
    }

    /** Anthropic requires an object schema; pad empty schemas. */
    private fun mergeSchemaWithDefaults(schema: JsonObject): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        if (schema.isEmpty()) {
            put("properties", JsonObject(emptyMap()))
        } else {
            schema.forEach { (key, value) -> put(key, value) }
        }
    }

    private class ToolUseAccumulator(val id: String, val name: String) {
        val partialJson = StringBuilder()
    }

    companion object {
        private const val API_BASE = "https://api.anthropic.com"
        private const val MAX_TOKENS = 8192
    }
}
