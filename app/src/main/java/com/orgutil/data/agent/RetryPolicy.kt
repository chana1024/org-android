package com.orgutil.data.agent

/**
 * Non-2xx from a provider, carrying what the retry policy needs. Raised by
 * the SSE clients instead of a bare IOException so the router can decide
 * retry vs surface-as-config-error without string matching.
 */
class LlmHttpException(
    val code: Int,
    val retryAfterSeconds: Long? = null,
    val bodySnippet: String = ""
) : Exception("HTTP $code${retryAfterSeconds?.let { " (Retry-After ${it}s)" } ?: ""}: $bodySnippet")

/** Tunable retry knobs; tests inject fast delays. */
data class RetrySpec(
    val maxAttempts: Int = 3,
    val baseDelayMillis: Long = 1_000,
    val maxDelayMillis: Long = 30_000
)

object RetryPolicy {

    /**
     * 429, overload-ish 5xx and transport failures get another attempt.
     * 501 (Not Implemented) is deliberately excluded - a permanent protocol
     * mismatch that retrying cannot fix - as are all other 4xx.
     */
    fun isRetryable(error: Throwable): Boolean = when (error) {
        is LlmHttpException -> isRetryable(error.code)
        else -> error is java.io.IOException
    }

    fun isRetryable(code: Int): Boolean =
        code == 429 || code == 500 || code == 502 || code == 503 || code == 504

    /**
     * Retry-After wins when present and positive (capped at [RetrySpec.maxDelayMillis]);
     * otherwise exponential backoff: base * 2^attempt.
     */
    fun backoffMillis(attempt: Int, retryAfterSeconds: Long?, spec: RetrySpec): Long {
        val fromHeader = retryAfterSeconds?.takeIf { it > 0 }?.times(1000)
        val exponential = spec.baseDelayMillis * (1L shl attempt.coerceAtMost(10))
        return (fromHeader ?: exponential).coerceIn(0, spec.maxDelayMillis)
    }
}
