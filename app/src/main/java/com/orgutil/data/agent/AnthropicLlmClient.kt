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
 * Anthropic Messages API streaming client (SSE), also serving
 * Anthropic-compatible gateways (DeepSeek /anthropic, GLM /api/anthropic).
 * Supports exactly what the agent loop needs: system prompt, tool
 * declarations, streamed text deltas and tool_use blocks. No retries, no
 * fallbacks here - the [ResilientLlmClient] router owns that; failures
 * surface as [LlmEvent.Failed] (HTTP failures as [LlmHttpException]).
 *
 * HOSTED WEB SEARCH (provider-native, server-executed): when
 * [webSearchEnabled], the request also declares the server-side
 * `web_search_20250305` tool. Its `server_tool_use` +
 * `web_search_tool_result` blocks arrive INSIDE this same streamed response;
 * they are surfaced as [LlmEvent.WebSearchStarted] / [WebSearchFinished]
 * (activity rows), NEVER executed through the local tool registry, and the
 * whole block array (encrypted_content included) is preserved in
 * [LlmEvent.TurnCompleted.nativeBlocks] so the next turn can replay it
 * verbatim - Anthropic 400s if search blocks are missing or modified.
 *
 * Tool definition shape is provider-honest: api.anthropic.com gets the full
 * documented definition (max_uses); compatible layers get the minimal
 * {type, name} form because their compatibility tables do not document the
 * extra options.
 *
 * `stop_reason: pause_turn` (long search turns are paused server-side) is
 * handled transparently: the assistant blocks built so far are resent
 * unchanged and generation continues (bounded to [MAX_PAUSE_CONTINUATIONS]
 * rounds; beyond that the turn ends honestly with what arrived).
 *
 * Cancellation: the OkHttp [Call] is retained and cancelled in awaitClose,
 * so stopping a run aborts the socket read immediately (read timeout is 0
 * for SSE - only an explicit cancel ends it).
 */
class AnthropicLlmClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val apiKey: String = "",
    override var modelName: String = DEFAULT_MODEL,
    private val webSearchEnabled: Boolean = false,
    private val webSearchMinimalDefinition: Boolean = false,
    private val webSearchMaxUses: Int = DEFAULT_WEB_SEARCH_MAX_USES
) : LlmClient {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Test hook: exposes the live call so cancellation can be asserted. */
    var onCallCreated: ((Call) -> Unit)? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // Bounded inter-byte timeout instead of an infinite SSE read: any
        // event/ping/keepalive byte resets it, so legitimate long hosted
        // searches keep streaming, while a silent or stuck connection
        // surfaces as Failed instead of an indefinite spinner.
        .readTimeout(STREAM_IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    override fun stream(
        systemPrompt: String,
        messages: List<LlmMessage>,
        tools: List<AgentTool>
    ): Flow<LlmEvent> = callbackFlow {
        // Shared with awaitClose so stopping the run aborts the live socket.
        var activeCall: Call? = null
        val job = launch(Dispatchers.IO) {
            // One logical turn may span several HTTP requests when the
            // server pauses a long search turn; text, tool uses, hosted
            // search blocks and native output merge across rounds.
            var conversation = messages
            val mergedText = StringBuilder()
            val mergedToolUses = mutableListOf<ToolUseBlock>()
            val mergedBlocks = mutableListOf<JsonObject>()
            var sawHostedSearch = false
            var pauseRounds = 0

            // REGRESSION GUARD (reply-loss fix): EVERY exit path from this
            // producer must close the channel - the collector otherwise
            // hangs in collect() forever (run never settles, assistant never
            // persisted, spinner forever). finally mirrors the other clients.
            try {
                while (true) {
                    val body = buildRequestBody(systemPrompt, conversation, tools)
                    val request = Request.Builder()
                        .url(resolveUrl("$baseUrl/v1/messages"))
                        .header("x-api-key", apiKey)
                        .header("anthropic-version", "2023-06-01")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()

                    var response: Response? = null
                    try {
                        activeCall = client.newCall(request).also { onCallCreated?.invoke(it) }
                        response = activeCall!!.execute()
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

                        val turn = TurnAccumulator()
                        var terminal = false
                        reader.useLines { lines ->
                            for (line in lines) {
                                if (!line.startsWith("data:")) continue
                                val payload = line.removePrefix("data:").trim()
                                if (payload.isEmpty() || payload == "[DONE]") continue
                                val event = runCatching {
                                    json.parseToJsonElement(payload).jsonObject
                                }.getOrNull() ?: continue
                                if (consumeEvent(event, turn) { e -> trySend(e) }) {
                                    terminal = true
                                    break
                                }
                                // Hosted-search signals are drained as they occur.
                                turn.drainSearchSignals { signal -> trySend(signal) }
                            }
                        }
                        if (turn.streamError) {
                            // Failed was already emitted by consumeEvent; a
                            // TurnCompleted on top would make the router count
                            // this turn as succeeded and swallow the failure.
                            return@launch
                        }
                        if (!terminal && turn.stopReason == null) {
                            // Socket ended without message_stop (gateway hiccup):
                            // fail when nothing arrived, otherwise complete
                            // honestly with the partial content received.
                            if (turn.isEmpty()) {
                                trySend(LlmEvent.Failed(IllegalStateException("Stream ended before message_stop")))
                                return@launch
                            }
                        }
                        turn.drainSearchSignals { signal -> trySend(signal) }

                        mergedText.append(turn.text())
                        mergedToolUses.addAll(turn.clientToolUses())
                        if (turn.sawHostedSearch) {
                            sawHostedSearch = true
                            mergedBlocks.addAll(turn.blocksView())
                        }

                        if (turn.stopReason == "pause_turn" && pauseRounds < MAX_PAUSE_CONTINUATIONS) {
                            pauseRounds++
                            // Resend the paused assistant blocks UNCHANGED; text
                            // accumulated so far belongs to the SAME logical turn.
                            val pausedBlocks = JsonArray(mergedBlocks.toList()).takeIf { it.isNotEmpty() }
                            conversation = conversation + LlmMessage.Assistant(
                                mergedText.toString(),
                                emptyList(),
                                pausedBlocks
                            )
                            continue
                        }

                        mergedToolUses.forEach { trySend(LlmEvent.ToolUseArrived(it)) }
                        trySend(
                            LlmEvent.TurnCompleted(
                                mergedText.toString(),
                                mergedToolUses.size,
                                if (sawHostedSearch) JsonArray(mergedBlocks.toList()) else null
                            )
                        )
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: java.net.SocketTimeoutException) {
                        trySend(
                            LlmEvent.Failed(
                                IllegalStateException(
                                    "连接空闲超时（${STREAM_IDLE_TIMEOUT_MS / 1000} 秒未收到任何数据）：" +
                                        "服务端长时间无响应，已停止等待。",
                                    e
                                )
                            )
                        )
                        return@launch
                    } catch (e: Exception) {
                        trySend(LlmEvent.Failed(e))
                        return@launch
                    } finally {
                        response?.close()
                    }
                }
            } finally {
                // Producer done (success, failure or cancellation): release
                // the collector. Idempotent when already closed/cancelled.
                close()
            }
        }

        awaitClose {
            // Cancelling the coroutine alone would NOT interrupt the
            // blocking read - cancel the call too.
            runCatching { activeCall?.cancel() }
            job.cancel()
        }
    }

    /**
     * Folds one SSE event into [turn]. Returns true when the turn is
     * finished (message_stop / terminal error) and reading should stop.
     */
    private fun consumeEvent(
        event: JsonObject,
        turn: TurnAccumulator,
        emit: (LlmEvent) -> Unit
    ): Boolean {
        when (event["type"]?.jsonPrimitive?.content) {
            "content_block_start" -> turn.onBlockStart(event["content_block"]?.jsonObject)
            "content_block_delta" -> {
                val delta = event["delta"]?.jsonObject ?: return false
                when (delta["type"]?.jsonPrimitive?.content) {
                    "text_delta" -> {
                        val t = delta["text"].stringOrNull() ?: ""
                        // Accumulate for native blocks / TurnCompleted.text AND
                        // forward live exactly once per delta - the loop and UI
                        // build the reply solely from TextDelta events (the
                        // reply-loss regression dropped this emission).
                        turn.onTextDelta(t)
                        if (t.isNotEmpty()) emit(LlmEvent.TextDelta(t))
                    }
                    "input_json_delta" -> turn.onInputJsonDelta(delta["partial_json"].stringOrNull() ?: "")
                }
            }
            "content_block_stop" -> turn.onBlockStop()
            "message_delta" -> {
                val delta = event["delta"]?.jsonObject
                delta?.get("stop_reason").stringOrNull()?.let { turn.stopReason = it }
            }
            "message_stop" -> return true
            "error" -> {
                // Mid-stream protocol error event: surface the provider text
                // and mark the turn aborted - no TurnCompleted may follow a
                // Failed, or the retry router counts the turn as succeeded.
                turn.streamError = true
                val err = event["error"]?.jsonObject
                emit(
                    LlmEvent.Failed(
                        LlmHttpException(
                            code = 200,
                            retryAfterSeconds = null,
                            bodySnippet = (err?.get("message").stringOrNull() ?: "stream error event")
                        )
                    )
                )
                return true
            }
        }
        return false
    }

    /** Joined URL, also used by tests to pin base-url normalization. */
    internal fun resolveUrl(joined: String): String = joined

    private fun buildRequestBody(
        systemPrompt: String,
        messages: List<LlmMessage>,
        tools: List<AgentTool>
    ): String {
        val sanitized = messages.filterNot { message ->
            message is LlmMessage.Assistant && message.text.isEmpty() && message.toolUses.isEmpty() &&
                message.nativeBlocks == null
        }
        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("model", modelName)
                put("max_tokens", MAX_TOKENS)
                put("stream", true)
                put("system", systemPrompt)
                put("messages", buildJsonArray {
                    sanitized.forEach { message -> add(serializeMessage(message)) }
                })
                put("tools", buildJsonArray {
                    tools.forEach { tool ->
                        add(
                            buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                // Same normalization contract as the OpenAI-compatible
                                // path: input_schema must be a JSON Schema type "object".
                                put("input_schema", tool.parametersSchema.normalizedAsObjectSchema())
                            }
                        )
                    }
                    if (webSearchEnabled) add(webSearchToolDefinition())
                })
            }
        )
    }

    /**
     * Hosted search tool declaration. api.anthropic.com accepts the full
     * documented form (max_uses); compatible layers get the minimal
     * {type, name} form because their tables do not document the options.
     */
    internal fun webSearchToolDefinition(): JsonObject = buildJsonObject {
        put("type", "web_search_20250305")
        put("name", "web_search")
        if (!webSearchMinimalDefinition) put("max_uses", webSearchMaxUses)
    }

    /** One LlmMessage -> one Messages-API message (native blocks replayed verbatim). */
    private fun serializeMessage(message: LlmMessage): JsonObject = buildJsonObject {
        when (message) {
            is LlmMessage.User -> {
                put("role", "user")
                put("content", buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", message.text)
                        }
                    )
                })
            }
            is LlmMessage.Assistant -> {
                put("role", "assistant")
                val native = message.nativeBlocks as? JsonArray
                if (native != null) {
                    // Exact replay of the provider's own block array (hosted
                    // search blocks with encrypted_content, citations, ids).
                    put("content", native)
                } else {
                    put("content", buildJsonArray {
                        if (message.text.isNotEmpty()) {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", message.text)
                                }
                            )
                        }
                        message.toolUses.forEach { toolUse ->
                            add(
                                buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", toolUse.id)
                                    put("name", toolUse.name)
                                    put("input", toolUse.args)
                                }
                            )
                        }
                    })
                }
            }
            is LlmMessage.ToolResults -> {
                put("role", "user")
                put("content", buildJsonArray {
                    message.results.forEach { result ->
                        add(
                            buildJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", result.toolUseId)
                                put("is_error", result.isError)
                                put("content", result.content)
                            }
                        )
                    }
                })
            }
        }
    }

    // ---- streaming state ----

    /**
     * Accumulates one streamed response round in BLOCK ORDER so the native
     * array can be rebuilt faithfully. Hosted search blocks are captured
     * verbatim; text is reassembled; client tool_use blocks are accumulated
     * as before (their native serialization is equivalent). Search signals
     * queue up and are drained by the reader loop, in arrival order.
     */
    private inner class TurnAccumulator {
        private val text = StringBuilder()
        private val blocks = mutableListOf<JsonObject>()
        private val toolUses = mutableListOf<ToolUseAccumulator>()
        private val searchSignals = mutableListOf<LlmEvent>()

        /** Index of the block currently streaming (content_block_* events). */
        private var currentIndex = -1

        /** Per-block accumulation state for reassembled blocks. */
        private var currentKind: String? = null
        private var currentText = StringBuilder()
        private var currentPartialJson = StringBuilder()
        private var currentServerInputJson = StringBuilder()

        var stopReason: String? = null
            set(value) {
                if (value != null) field = value
            }

        /** A terminal stream `error` event arrived; the turn must not complete. */
        var streamError = false

        var sawHostedSearch = false
            private set

        fun onBlockStart(block: JsonObject?) {
            val kind = block?.get("type")?.jsonPrimitive?.content ?: return
            currentIndex = blocks.size
            currentKind = kind
            currentText = StringBuilder()
            currentPartialJson = StringBuilder()
            currentServerInputJson = StringBuilder()
            when (kind) {
                "tool_use" -> {
                    toolUses.add(
                        ToolUseAccumulator(
                            // Boundary: JsonNull.content reads as "null";
                            // only genuine strings pass.
                            id = block["id"].stringOrNull() ?: "",
                            name = block["name"].stringOrNull() ?: ""
                        )
                    )
                    blocks.add(block)
                }
                // web_search_tool_result arrives complete in the start event;
                // keep the provider object verbatim (encrypted_content, ids).
                "web_search_tool_result" -> {
                    sawHostedSearch = true
                    blocks.add(block)
                }
                "server_tool_use" -> {
                    sawHostedSearch = true
                    blocks.add(block) // input streamed via input_json_delta
                }
                else -> blocks.add(block) // text and unknown kinds: keep frame
            }
        }

        fun onTextDelta(t: String) {
            text.append(t)
            currentText.append(t)
        }

        fun onInputJsonDelta(fragment: String) {
            when (currentKind) {
                "tool_use" -> if (toolUses.isNotEmpty()) {
                    toolUses.last().partialJson.append(fragment)
                }
                "server_tool_use" -> currentServerInputJson.append(fragment)
            }
        }

        /** Finalizes the current block; queues hosted-search signals. */
        fun onBlockStop() {
            val index = currentIndex
            val kind = currentKind
            if (index < 0 || index >= blocks.size || kind == null) {
                resetCursor()
                return
            }
            when (kind) {
                "text" -> blocks[index] = buildJsonObject {
                    put("type", "text")
                    put("text", currentText.toString())
                }
                // LOCAL client tool_use must replay with its streamed input:
                // the start frame carries input:{} and arguments arrive via
                // input_json_delta, so the block is rebuilt from the
                // accumulator - interleaved local tools survive native replay.
                "tool_use" -> {
                    val acc = toolUses.lastOrNull() ?: run {
                        resetCursor()
                        return
                    }
                    val args = if (acc.partialJson.isBlank()) JsonObject(emptyMap())
                    else runCatching { json.parseToJsonElement(acc.partialJson.toString()).jsonObject }
                        .getOrElse { JsonObject(emptyMap()) }
                    blocks[index] = buildJsonObject {
                        put("type", "tool_use")
                        put("id", acc.id)
                        put("name", acc.name)
                        put("input", args)
                    }
                }
                "server_tool_use" -> {
                    val input = parseInputObject(currentServerInputJson.toString())
                    val rebuilt = buildJsonObject {
                        blocks[index].forEach { (key, value) -> put(key, value) }
                        put("input", input)
                    }
                    blocks[index] = rebuilt
                    searchSignals.add(
                        LlmEvent.WebSearchStarted(
                            id = rebuilt["id"].stringOrNull().orEmpty(),
                            query = input["query"].stringOrNull().orEmpty()
                        )
                    )
                }
                "web_search_tool_result" -> {
                    val block = blocks[index]
                    val (sources, error) = parseSearchResults(block["content"])
                    searchSignals.add(
                        LlmEvent.WebSearchFinished(
                            id = block["tool_use_id"].stringOrNull().orEmpty(),
                            queries = emptyList(),
                            sources = sources,
                            error = error
                        )
                    )
                }
            }
            resetCursor()
        }

        fun drainSearchSignals(emit: (LlmEvent) -> Unit) {
            while (searchSignals.isNotEmpty()) {
                emit(searchSignals.removeAt(0))
            }
        }

        fun isEmpty(): Boolean = blocks.isEmpty() && text.isEmpty()

        fun text(): String = text.toString()

        fun clientToolUses(): List<ToolUseBlock> = toolUses.map { accumulator ->
            val args = if (accumulator.partialJson.isBlank()) JsonObject(emptyMap())
            else runCatching { json.parseToJsonElement(accumulator.partialJson.toString()).jsonObject }
                .getOrElse { JsonObject(emptyMap()) }
            ToolUseBlock(accumulator.id, accumulator.name, args)
        }

        fun blocksView(): List<JsonObject> = blocks.toList()

        private fun resetCursor() {
            currentIndex = -1
            currentKind = null
            currentText = StringBuilder()
            currentPartialJson = StringBuilder()
            currentServerInputJson = StringBuilder()
        }

        private fun parseInputObject(raw: String): JsonObject =
            if (raw.isBlank()) JsonObject(emptyMap())
            else runCatching { json.parseToJsonElement(raw).jsonObject }.getOrDefault(JsonObject(emptyMap()))
    }

    /** Extracts sources / error from a web_search_tool_result content value. */
    private fun parseSearchResults(content: JsonElement?): Pair<List<WebSearchSource>, String?> {
        // Error form: content is a single object {type: web_search_tool_result_error, error_code}.
        val errorObject = content as? JsonObject
        if (errorObject != null) {
            val code = errorObject["error_code"].stringOrNull()
                ?: errorObject["type"].stringOrNull()
                ?: "web_search_error"
            return emptyList<WebSearchSource>() to code
        }
        val array = (content as? JsonArray) ?: return emptyList<WebSearchSource>() to null
        val sources = array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val url = obj["url"].stringOrNull() ?: return@mapNotNull null
            WebSearchSource(url = url, title = obj["title"].stringOrNull())
        }
        return sources to null
    }

    private class ToolUseAccumulator(val id: String, val name: String) {
        val partialJson = StringBuilder()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        const val DEFAULT_MODEL = "claude-sonnet-5"
        private const val MAX_TOKENS = 8192
        private const val DEFAULT_WEB_SEARCH_MAX_USES = 5
        private const val MAX_PAUSE_CONTINUATIONS = 3

        /** Inter-byte SSE timeout: bounds silent connections without hurting long searches. */
        const val STREAM_IDLE_TIMEOUT_MS = 180_000L
    }
}
