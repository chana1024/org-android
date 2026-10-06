package com.orgutil.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins FM-P3/P4: which HTTP failures may burn retries and which must
 * surface immediately as configuration errors, and how backoff honors
 * Retry-After (capped) versus exponential growth.
 */
class RetryPolicyTest {

    @Test
    fun `429 and 5xx are retryable, config and client errors are not`() {
        assertTrue(RetryPolicy.isRetryable(429))
        assertTrue(RetryPolicy.isRetryable(500))
        assertTrue(RetryPolicy.isRetryable(502))
        assertTrue(RetryPolicy.isRetryable(503))
        assertTrue(RetryPolicy.isRetryable(504))

        assertFalse("401 must surface immediately (dead key)", RetryPolicy.isRetryable(401))
        assertFalse("403 must surface immediately", RetryPolicy.isRetryable(403))
        assertFalse(RetryPolicy.isRetryable(400))
        assertFalse(RetryPolicy.isRetryable(404))
        assertFalse(RetryPolicy.isRetryable(422))
        assertFalse("501 NOT IMPLEMENTED is a permanent server/config mismatch", RetryPolicy.isRetryable(501))
    }

    @Test
    fun `connection failures are retryable`() {
        assertTrue(RetryPolicy.isRetryable(java.io.IOException("connect reset")))
    }

    @Test
    fun `retry-after wins over exponential backoff and is capped`() {
        val spec = RetrySpec(maxAttempts = 3, baseDelayMillis = 100, maxDelayMillis = 30_000)

        assertEquals(1_000, RetryPolicy.backoffMillis(0, retryAfterSeconds = 1, spec = spec))
        assertEquals(30_000, RetryPolicy.backoffMillis(0, retryAfterSeconds = 120, spec = spec))
        assertEquals(30_000, RetryPolicy.backoffMillis(1, retryAfterSeconds = 45, spec = spec))
    }

    @Test
    fun `without retry-after backoff grows exponentially with the attempt`() {
        val spec = RetrySpec(maxAttempts = 3, baseDelayMillis = 100, maxDelayMillis = 30_000)

        assertEquals(100, RetryPolicy.backoffMillis(0, retryAfterSeconds = null, spec = spec))
        assertEquals(200, RetryPolicy.backoffMillis(1, retryAfterSeconds = null, spec = spec))
        assertEquals(400, RetryPolicy.backoffMillis(2, retryAfterSeconds = null, spec = spec))
    }

    @Test
    fun `negative or zero retry-after falls back to exponential`() {
        val spec = RetrySpec(maxAttempts = 3, baseDelayMillis = 100, maxDelayMillis = 30_000)
        assertEquals(100, RetryPolicy.backoffMillis(0, retryAfterSeconds = 0, spec = spec))
        assertEquals(200, RetryPolicy.backoffMillis(1, retryAfterSeconds = -5, spec = spec))
    }
}
