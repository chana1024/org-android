package com.orgutil.data.agent

import com.orgutil.domain.chat.LlmClient
import com.orgutil.domain.chat.LlmEvent
import com.orgutil.domain.chat.LlmMessage
import com.orgutil.domain.chat.AgentTool
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** A named, resolved provider (profile name + ready client). */
data class ResolvedProvider(val name: String, val client: LlmClient)

/**
 * Retry/fallback router around provider clients.
 *
 * - Retry (same profile): only connect/IO failures, HTTP 429 and 5xx, at
 *   most [RetrySpec.maxAttempts] attempts with Retry-After-honoring
 *   exponential backoff. 401/403/4xx surface immediately as configuration
 *   errors.
 * - NEVER re-requests after the stream already delivered content or tool
 *   blocks - a retry would splice a duplicated reply onto the partial one.
 * - Fallback: exactly ONE attempt on the user-configured fallback profile
 *   (never guessed), announced via [LlmEvent.ProviderSwitched]; a fallback
 *   failure is final (no loops, no third provider).
 * - Cancellation propagates: cancelling the collector cancels the active
 *   attempt's OkHttp call and aborts the retry loop.
 */
class ResilientLlmClient(
    private val primaryProvider: suspend () -> ResolvedProvider?,
    private val fallbackProvider: suspend () -> ResolvedProvider?,
    private val spec: RetrySpec = RetrySpec()
) : LlmClient {

    /**
     * Last profile name the router resolved while streaming. Kept as a
     * plain snapshot - the getter must never run provider resolution (that
     * touches storage); the authoritative "who served this run" signal is
     * [LlmEvent.ProviderActive] / [LlmEvent.ProviderSwitched].
     */
    @Volatile
    private var lastResolvedName: String = "unconfigured"

    override val modelName: String
        get() = lastResolvedName

    override fun stream(
        systemPrompt: String,
        messages: List<LlmMessage>,
        tools: List<AgentTool>
    ): Flow<LlmEvent> = flow {
        val primary = primaryProvider()
            ?: run {
                emit(LlmEvent.Failed(IllegalStateException("No LLM provider configured - add a provider profile first.")))
                return@flow
            }
        lastResolvedName = primary.client.modelName
        emit(LlmEvent.ProviderActive(primary.name))

        var lastError: Throwable? = null
        var succeeded = false
        for (attempt in 0 until spec.maxAttempts) {
            var contentDelivered = false
            var failure: Throwable? = null

            primary.client.stream(systemPrompt, messages, tools).collect { event ->
                when (event) {
                    is LlmEvent.TextDelta -> { contentDelivered = true; emit(event) }
                    is LlmEvent.ToolUseArrived -> { contentDelivered = true; emit(event) }
                    // Hosted (server-side) search events are provider content:
                    // forwarded like any other streamed content (and they too
                    // mark the boundary after which a retry would duplicate).
                    is LlmEvent.WebSearchStarted -> { contentDelivered = true; emit(event) }
                    is LlmEvent.WebSearchFinished -> { contentDelivered = true; emit(event) }
                    is LlmEvent.TurnCompleted -> { succeeded = true; emit(event) }
                    is LlmEvent.Failed -> failure = event.error
                    is LlmEvent.ProviderSwitched, is LlmEvent.ProviderActive -> Unit
                }
            }

            if (succeeded) return@flow
            val error = failure ?: IllegalStateException("Stream ended without completion")
            lastError = error

            if (contentDelivered) {
                // Partial content already reached the UI/history - a retry
                // would duplicate it. Surface the boundary honestly.
                emit(
                    LlmEvent.Failed(
                        IllegalStateException(
                            "连接中断，且已收到部分内容（为避免重复回复不再自动重试）：${error.message}",
                            error
                        )
                    )
                )
                return@flow
            }

            if (!RetryPolicy.isRetryable(error) || attempt == spec.maxAttempts - 1) break

            val retryAfter = (error as? LlmHttpException)?.retryAfterSeconds
            delay(RetryPolicy.backoffMillis(attempt, retryAfter, spec))
        }

        // User-configured fallback only; exactly one attempt, announced.
        val fallback = fallbackProvider()
        if (fallback == null || fallback.name == primary.name) {
            emit(LlmEvent.Failed(lastError ?: IllegalStateException("LLM stream failed")))
            return@flow
        }
        emit(LlmEvent.ProviderSwitched(fallback.name))
        var fallbackContent = false
        var fallbackFailure: Throwable? = null
        var fallbackSucceeded = false
        fallback.client.stream(systemPrompt, messages, tools).collect { event ->
            when (event) {
                is LlmEvent.TextDelta -> { fallbackContent = true; emit(event) }
                is LlmEvent.ToolUseArrived -> { fallbackContent = true; emit(event) }
                is LlmEvent.WebSearchStarted -> { fallbackContent = true; emit(event) }
                is LlmEvent.WebSearchFinished -> { fallbackContent = true; emit(event) }
                is LlmEvent.TurnCompleted -> { fallbackSucceeded = true; emit(event) }
                is LlmEvent.Failed -> fallbackFailure = event.error
                is LlmEvent.ProviderSwitched, is LlmEvent.ProviderActive -> Unit
            }
        }
        if (!fallbackSucceeded) {
            val error = fallbackFailure ?: IllegalStateException("Fallback stream ended without completion")
            if (fallbackContent) {
                emit(
                    LlmEvent.Failed(
                        IllegalStateException(
                            "备用模型连接中断，且已收到部分内容（不再自动重试）：${error.message}", error
                        )
                    )
                )
            } else {
                emit(LlmEvent.Failed(error))
            }
        }
    }
}
