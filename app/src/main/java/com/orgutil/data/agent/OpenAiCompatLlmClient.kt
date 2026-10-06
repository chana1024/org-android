package com.orgutil.data.agent

import com.orgutil.domain.chat.AgentTool
import com.orgutil.domain.chat.LlmClient
import com.orgutil.domain.chat.LlmEvent
import com.orgutil.domain.chat.LlmMessage
import com.orgutil.domain.chat.ToolUseBlock
import com.orgutil.domain.chat.WebSearchSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

/**
 * OpenAI-compatible Chat Completions streaming client (SSE) - works with
 * any gateway that implements the protocol (configurable base URL, e.g. a
 * self-hosted "New API" entrance).
 *
 * Protocol mapping:
 * - system prompt -> first {role:"system"} message;
 * - LlmMessage.User -> {role:"user"};
 * - LlmMessage.Assistant(toolUses) -> assistant message with a tool_calls
 *   array [{id, type:"function", function:{name, arguments:<json-string>}}];
 * - LlmMessage.ToolResults -> one {role:"tool", tool_call_id, content}
 *   message per result;
 * - tools -> [{type:"function", function:{name, description, parameters}}].
 *
 * HOSTED WEB SEARCH (GLM shape): when [webSearchEnabled] (智谱 paas/v4 Chat
 * Completions documents this exact form), a
 * {type:"web_search", web_search:{enable, search_engine...}} CONFIG TOOL is
 * appended. Search executes SERVER-SIDE; provider-reported result entries
 * (a `web_search` array on the message) are surfaced as
 * [LlmEvent.WebSearchStarted]/[WebSearchFinished] activity rows - never
 * executed through the local registry. Any non-"function" entry inside
 * streamed tool_calls frames is likewise a hosted tool call frame and is
 * SKIPPED, so it can never reach the local "Unknown tool" path.
 *
 * Streaming: tool_calls arrive as per-index argument string DELTAS which
 * are merged by index; [DONE] ends the stream.
 *
 * Cancellation mirrors [AnthropicLlmClient]: the OkHttp call is cancelled
 * in awaitClose so stop truly aborts the socket.
 */
class OpenAiCompatLlmClient(
    private val baseUrl: String,
    private val apiKey: String,
    override var modelName: String,
    private val webSearchEnabled: Boolean = false
) : LlmClient {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Test hook: exposes the live call so cancellation can be asserted. */
    var onCallCreated: ((Call) -> Unit)? = null

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
            .url(resolveUrlForTest(baseUrl))
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        var callRef: Call? = null
        val job = launch(Dispatchers.IO) {
            var response: Response? = null
            try {
                callRef = client.newCall(request).also { onCallCreated?.invoke(it) }
                response = callRef!!.execute()
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty().take(500)
                    trySend(
                        LlmEvent.Failed(
                            LlmHttpException(
                                code = response.code,
                                retryAfterSeconds = response.header("Retry-After")?.trim()?.toLongOrNull(),
                                bodySnippet = errorBody
                            )
                        )
                    )
                    return@launch
                }
                val reader: BufferedReader = response.body?.charStream()?.buffered()
                    ?: run {
                        trySend(LlmEvent.Failed(IllegalStateException("Empty response body")))
                        return@launch
                    }

                val text = StringBuilder()
                val toolAccumulators = sortedMapOf<Int, ToolCallAccumulator>()
                // GLM hosted-search evidence, if the provider reports any.
                val searchSources = linkedMapOf<String, WebSearchSource>()
                var searchSignalled = false

                fun maybeEmitSearch() {
                    if (searchSources.isEmpty() || searchSignalled) return
                    searchSignalled = true
                    trySend(LlmEvent.WebSearchStarted(id = GLM_SEARCH_ID, query = ""))
                    trySend(
                        LlmEvent.WebSearchFinished(
                            id = GLM_SEARCH_ID,
                            queries = emptyList(),
                            sources = searchSources.values.toList(),
                            error = null
                        )
                    )
                }

                reader.useLines { lines ->
                    for (line in lines) {
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload.isEmpty()) continue
                        if (payload == "[DONE]") break

                        val event = runCatching { json.parseToJsonElement(payload).jsonObject }
                            .getOrNull() ?: continue

                        // Provider-reported hosted search results (GLM): a
                        // web_search array may ride the delta or the chunk.
                        extractSearchSources(event["web_search"]?.jsonArray, searchSources)
                        val choice = event["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                        extractSearchSources(choice["web_search"]?.jsonArray, searchSources)
                        val delta = choice["delta"]?.jsonObject ?: continue
                        extractSearchSources(delta["web_search"]?.jsonArray, searchSources)

                        // Boundary: JsonNull.content would read as the literal
                        // string "null"; only genuine string content passes.
                        delta["content"].stringOrNull()?.takeIf { it.isNotEmpty() }?.let { t ->
                            text.append(t)
                            trySend(LlmEvent.TextDelta(t))
                        }

                        delta["tool_calls"]?.jsonArray?.forEach { rawCall ->
                            val callDelta = rawCall.jsonObject
                            // Hosted tool frames (GLM may stream a web_search
                            // call back) are NEVER local function calls.
                            val frameType = callDelta["type"].stringOrNull()
                            if (frameType != null && frameType != "function") {
                                if (frameType == "web_search") maybeEmitSearch()
                                return@forEach
                            }
                            val index = callDelta["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                            val acc = toolAccumulators.getOrPut(index) { ToolCallAccumulator() }
                            callDelta["id"].stringOrNull()?.let { acc.id = it }
                            val function = callDelta["function"]?.jsonObject
                            function?.get("name").stringOrNull()?.let { acc.name = it }
                            function?.get("arguments").stringOrNull()?.let { acc.arguments.append(it) }
                        }
                    }
                }
                // Search evidence discovered anywhere in the stream surfaces
                // once, after the stream ends (GLM reports it as a whole).
                maybeEmitSearch()

                toolAccumulators.values.forEach { acc ->
                    val args = if (acc.arguments.isBlank()) JsonObject(emptyMap())
                    else runCatching { json.parseToJsonElement(acc.arguments.toString()).jsonObject }
                        .getOrElse { JsonObject(emptyMap()) }
                    val id = acc.id.ifBlank { "call_${toolAccumulators.hashCode()}_$acc" }
                    trySend(LlmEvent.ToolUseArrived(ToolUseBlock(id, acc.name, args)))
                }
                trySend(LlmEvent.TurnCompleted(text.toString(), toolAccumulators.size))
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
            runCatching { callRef?.cancel() }
            job.cancel()
        }
    }

    /**
     * Base URL normalization: as-is when it already ends with
     * /chat/completions; append /chat/completions when it ends with /v1
     * (or /v1/); otherwise append /v1/chat/completions.
     */
    fun resolveUrlForTest(base: String): String {
        val trimmed = base.trimEnd('/')
        return when {
            trimmed.endsWith("/chat/completions") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
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
                put("stream", true)
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    sanitized.forEach { message ->
                        when (message) {
                            is LlmMessage.User -> add(
                                buildJsonObject {
                                    put("role", "user")
                                    put("content", message.text)
                                }
                            )
                            is LlmMessage.Assistant -> add(
                                buildJsonObject {
                                    put("role", "assistant")
                                    if (message.text.isNotEmpty()) put("content", message.text)
                                    if (message.toolUses.isNotEmpty()) {
                                        put("tool_calls", buildJsonArray {
                                            message.toolUses.forEach { toolUse ->
                                                add(
                                                    buildJsonObject {
                                                        put("id", toolUse.id)
                                                        put("type", "function")
                                                        put(
                                                            "function",
                                                            buildJsonObject {
                                                                put("name", toolUse.name)
                                                                put("arguments", toolUse.args.toString())
                                                            }
                                                        )
                                                    }
                                                )
                                            }
                                        })
                                    }
                                }
                            )
                            is LlmMessage.ToolResults -> message.results.forEach { result ->
                                add(
                                    buildJsonObject {
                                        put("role", "tool")
                                        put("tool_call_id", result.toolUseId)
                                        put("content", result.content)
                                    }
                                )
                            }
                        }
                    }
                })
                if (tools.isNotEmpty() || webSearchEnabled) {
                    put("tools", buildJsonArray {
                        tools.forEach { tool ->
                            add(
                                buildJsonObject {
                                    put("type", "function")
                                    put(
                                        "function",
                                        buildJsonObject {
                                            put("name", tool.name)
                                            put("description", tool.description)
                                            // Strict gateways (DeepSeek) 400 unless parameters
                                            // is a JSON Schema type "object"; normalize every
                                            // registered tool (empty parameterless schemas).
                                            put("parameters", tool.parametersSchema.normalizedAsObjectSchema())
                                        }
                                    )
                                }
                            )
                        }
                        if (webSearchEnabled) add(glmWebSearchTool())
                    })
                }
            }
        )
    }

    /**
     * GLM's documented Chat-Completions hosted search CONFIG tool: the
     * server executes searches and folds results into the answer itself.
     * search_result=true asks the provider to also return the result set it
     * used (surfaced as activity sources when present).
     */
    internal fun glmWebSearchTool(): JsonObject = buildJsonObject {
        put("type", "web_search")
        put(
            "web_search",
            buildJsonObject {
                put("enable", true)
                put("search_engine", "search_pro")
                put("search_result", true)
            }
        )
    }

    /** Collects provider-reported search entries ({title, link, ...}) as sources. */
    private fun extractSearchSources(array: JsonArray?, into: MutableMap<String, WebSearchSource>) {
        array?.forEach { raw ->
            val entry = raw as? JsonObject ?: return@forEach
            val link = entry["link"].stringOrNull() ?: return@forEach
            if (link !in into) {
                into[link] = WebSearchSource(url = link, title = entry["title"].stringOrNull())
            }
        }
    }

    private class ToolCallAccumulator {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }

    companion object {
        /** Synthetic activity-row id for GLM chat hosted search (one per turn). */
        const val GLM_SEARCH_ID = "glm_chat_web_search"
    }
}
