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
import kotlinx.serialization.json.JsonElement
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
 * OpenAI Responses protocol streaming client (SSE) for OpenAI, GLM's native
 * Responses API and DeepSeek's Responses-compatible API.
 *
 * Base-URL reality per provider docs:
 * - OpenAI: https://api.openai.com/v1 -> POST /v1/responses
 * - GLM:    https://open.bigmodel.cn/api/v1 -> POST /responses (NOT the
 *   Chat-Completions /api/paas/v4 base)
 * - DeepSeek: https://api.deepseek.com -> POST /responses
 * [resolveUrl] accepts bases AND full endpoints; a URL already ending in
 * /responses is used as-is, custom gateway paths are retained.
 *
 * Protocol mapping (stateless - the app keeps the whole history, GLM's
 * store=false default included):
 * - system prompt -> instructions;
 * - User -> message{role:user, content:[input_text]};
 * - Assistant -> message{role:assistant, content:[output_text]} plus one
 *   function_call item per client tool use (call_id = tool_use id);
 * - ToolResults -> one function_call_output item per result, call_id-paired;
 * - client tools -> {type:"function", name, description, parameters};
 * - HOSTED SEARCH (OpenAI + GLM): {type:"web_search"} tool declaration.
 *   web_search_call OUTPUT items and their events are surfaced as
 *   [LlmEvent.WebSearchStarted]/[WebSearchFinished] activity rows and are
 *   NEVER executed through the local registry.
 *
 * Native replay: assistant turns that contained hosted search persist the
 * provider's output items verbatim ([LlmMessage.Assistant.nativeBlocks]).
 * On replay only `message` and `function_call` items are resent -
 * web_search_call/reasoning items are dropped (GLM does not document
 * accepting them in input; the model already consumed the results).
 *
 * Stream termination: response.completed / response.failed /
 * response.incomplete / error events END the read; GLM never sends
 * data: [DONE] while OpenAI/DeepSeek-compatible gateways may - it is
 * accepted but never required.
 *
 * Cancellation mirrors [AnthropicLlmClient]: the OkHttp call is cancelled in
 * awaitClose so stop truly aborts the socket.
 */
class ResponsesLlmClient(
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
            .url(resolveUrl(baseUrl))
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
                // function_call accumulators keyed by the provider item id.
                val functionCalls = LinkedHashMap<String, FunctionCallAccumulator>()
                // web_search_call items keyed by id; queries/sources come from
                // the completed item (action.queries / result).
                val searchQueries = HashMap<String, List<String>>()
                val searchSources = HashMap<String, List<WebSearchSource>>()
                val announcedSearches = mutableSetOf<String>()
                var sawHostedSearch = false
                var failed: Throwable? = null
                var completed = false
                var nativeOutput: JsonElement? = null

                fun announceSearch(id: String) {
                    if (id.isNotEmpty() && id in announcedSearches) return
                    if (id.isNotEmpty()) announcedSearches.add(id)
                    sawHostedSearch = true
                    trySend(LlmEvent.WebSearchStarted(id = id, query = searchQueries[id]?.firstOrNull().orEmpty()))
                }

                fun finishSearch(id: String) {
                    if (id.isEmpty()) return
                    sawHostedSearch = true
                    announceSearch(id)
                    trySend(
                        LlmEvent.WebSearchFinished(
                            id = id,
                            queries = searchQueries[id].orEmpty(),
                            sources = searchSources[id].orEmpty(),
                            error = null
                        )
                    )
                }

                reader.useLines { lines ->
                    lineLoop@ for (line in lines) {
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload.isEmpty()) continue
                        if (payload == "[DONE]") break
                        val event = runCatching { json.parseToJsonElement(payload).jsonObject }
                            .getOrNull() ?: continue
                        val type = event["type"]?.jsonPrimitive?.content ?: continue
                        when (type) {
                            "response.output_text.delta" -> {
                                val t = event["delta"].stringOrNull() ?: ""
                                if (t.isNotEmpty()) {
                                    text.append(t)
                                    trySend(LlmEvent.TextDelta(t))
                                }
                            }

                            "response.output_item.added" -> {
                                val item = event["item"]?.jsonObject ?: continue
                                when (item["type"]?.jsonPrimitive?.content) {
                                    "function_call" -> functionCalls.getOrPut(
                                        item["id"].stringOrNull().orEmpty()
                                    ) { FunctionCallAccumulator(callId = item["call_id"].stringOrNull().orEmpty()) }
                                        .apply {
                                            if (name.isEmpty()) name = item["name"].stringOrNull().orEmpty()
                                        }
                                    "web_search_call" -> announceSearch(item["id"].stringOrNull().orEmpty())
                                }
                            }

                            "response.function_call_arguments.delta" -> {
                                val id = event["item_id"].stringOrNull().orEmpty()
                                functionCalls.getOrPut(id) { FunctionCallAccumulator(callId = "") }
                                    .arguments.append(event["delta"].stringOrNull() ?: "")
                            }

                            "response.output_item.done" -> {
                                val item = event["item"]?.jsonObject ?: continue
                                when (item["type"]?.jsonPrimitive?.content) {
                                    "function_call" -> {
                                        val id = item["id"].stringOrNull().orEmpty()
                                        val acc = functionCalls.getOrPut(id) { FunctionCallAccumulator(callId = "") }
                                        if (acc.name.isEmpty()) acc.name = item["name"].stringOrNull().orEmpty()
                                        if (acc.callId.isEmpty()) acc.callId = item["call_id"].stringOrNull().orEmpty()
                                        val args = item["arguments"].stringOrNull()
                                        if (!args.isNullOrEmpty()) acc.arguments.clear().append(args)
                                    }
                                    "web_search_call" -> {
                                        val id = item["id"].stringOrNull().orEmpty()
                                        val action = item["action"]?.jsonObject
                                        searchQueries[id] = action?.get("queries")?.jsonArray
                                            ?.mapNotNull { it.stringOrNull() }.orEmpty()
                                        searchSources[id] = extractSearchSources(item["result"])
                                        finishSearch(id)
                                    }
                                }
                            }

                            "response.web_search_call.in_progress",
                            "response.web_search_call.searching" ->
                                announceSearch(event["item_id"].stringOrNull().orEmpty())

                            "response.completed", "response.incomplete" -> {
                                // Terminal; carries the full response object.
                                nativeOutput = event["response"]?.jsonObject?.get("output")
                                completed = true
                                break@lineLoop
                            }

                            "response.failed" -> {
                                val err = event["response"]?.jsonObject?.get("error")?.jsonObject
                                failed = LlmHttpException(
                                    code = 200,
                                    retryAfterSeconds = null,
                                    bodySnippet = "response.failed: " +
                                        (err?.get("code").stringOrNull() ?: "unknown") + " " +
                                        (err?.get("message").stringOrNull() ?: "")
                                )
                                completed = true
                                break@lineLoop
                            }

                            "error" -> {
                                failed = LlmHttpException(
                                    code = 200,
                                    retryAfterSeconds = null,
                                    bodySnippet = "error: " +
                                        (event["code"].stringOrNull() ?: "unknown") + " " +
                                        (event["message"].stringOrNull() ?: "")
                                )
                                completed = true
                                break@lineLoop
                            }
                        }
                    }
                }

                failed?.let {
                    trySend(LlmEvent.Failed(it))
                    return@launch
                }
                if (!completed) {
                    trySend(LlmEvent.Failed(IllegalStateException("Stream ended without a terminal response event")))
                    return@launch
                }

                functionCalls.values.forEach { acc ->
                    val args = if (acc.arguments.isBlank()) JsonObject(emptyMap())
                    else runCatching { json.parseToJsonElement(acc.arguments.toString()).jsonObject }
                        .getOrElse { JsonObject(emptyMap()) }
                    val callId = acc.callId.ifBlank { "call_${acc.hashCode()}" }
                    trySend(LlmEvent.ToolUseArrived(ToolUseBlock(callId, acc.name, args)))
                }
                // Native replay payload: only meaningful when hosted search
                // happened (ordinary turns replay via text + function_call
                // reconstruction, which stays fully supported).
                trySend(
                    LlmEvent.TurnCompleted(
                        text.toString(),
                        functionCalls.size,
                        nativeOutput?.takeIf { sawHostedSearch }
                    )
                )
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
     * Base URL normalization: as-is when it already ends with /responses;
     * append /responses otherwise (versioned tails like /v1, /api/v1 and
     * bare hosts all get a plain /responses - custom gateway paths survive).
     */
    fun resolveUrl(base: String): String {
        val trimmed = base.trimEnd('/')
        return if (trimmed.endsWith("/responses")) trimmed else "$trimmed/responses"
    }

    private fun buildRequestBody(
        systemPrompt: String,
        messages: List<LlmMessage>,
        tools: List<AgentTool>
    ): String {
        val input = buildJsonArray {
            messages.forEach { message ->
                when (message) {
                    is LlmMessage.User -> add(
                        buildJsonObject {
                            put("type", "message")
                            put("role", "user")
                            put("content", buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "input_text")
                                        put("text", message.text)
                                    }
                                )
                            })
                        }
                    )
                    is LlmMessage.Assistant -> {
                        val native = message.nativeBlocks as? JsonArray
                        if (native != null) {
                            // Replay only items the providers document for
                            // input; hosted-search/reasoning items are dropped
                            // (their content was already consumed server-side).
                            native.forEach { item ->
                                val type = (item as? JsonObject)?.get("type")?.jsonPrimitive?.content
                                if (type == "message" || type == "function_call") add(item)
                            }
                        } else {
                            if (message.text.isNotEmpty()) {
                                add(
                                    buildJsonObject {
                                        put("type", "message")
                                        put("role", "assistant")
                                        put("content", buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("type", "output_text")
                                                    put("text", message.text)
                                                }
                                            )
                                        })
                                    }
                                )
                            }
                            message.toolUses.forEach { toolUse ->
                                add(
                                    buildJsonObject {
                                        put("type", "function_call")
                                        put("name", toolUse.name)
                                        put("call_id", toolUse.id)
                                        put("arguments", toolUse.args.toString())
                                    }
                                )
                            }
                        }
                    }
                    is LlmMessage.ToolResults -> message.results.forEach { result ->
                        add(
                            buildJsonObject {
                                put("type", "function_call_output")
                                put("call_id", result.toolUseId)
                                put("output", result.content)
                            }
                        )
                    }
                }
            }
        }
        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("model", modelName)
                put("instructions", systemPrompt)
                put("input", input)
                put("stream", true)
                if (tools.isNotEmpty() || webSearchEnabled) {
                    put("tools", buildJsonArray {
                        tools.forEach { tool ->
                            add(
                                buildJsonObject {
                                    put("type", "function")
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    // Same normalization contract as the other
                                    // clients: parameters must be an object schema.
                                    put("parameters", tool.parametersSchema.normalizedAsObjectSchema())
                                }
                            )
                        }
                        if (webSearchEnabled) add(buildJsonObject { put("type", "web_search") })
                    })
                }
            }
        )
    }

    /** web_search_call.result entries -> sources (provider-reported only). */
    private fun extractSearchSources(result: JsonElement?): List<WebSearchSource> {
        val array = result as? JsonArray ?: return emptyList()
        return array.mapNotNull { raw ->
            val obj = raw as? JsonObject ?: return@mapNotNull null
            val url = obj["url"].stringOrNull() ?: obj["link"].stringOrNull() ?: return@mapNotNull null
            WebSearchSource(url = url, title = obj["title"].stringOrNull())
        }
    }

    private class FunctionCallAccumulator(var callId: String) {
        var name: String = ""
        val arguments = StringBuilder()
    }
}
