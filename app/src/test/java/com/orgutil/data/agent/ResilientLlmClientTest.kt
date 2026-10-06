package com.orgutil.data.agent

import com.orgutil.domain.chat.LlmEvent
import com.orgutil.domain.chat.LlmMessage
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins FM-P3..P7 against real loopback HTTP: bounded retries with
 * Retry-After, no blind retry on 401, no re-request after content was
 * already streamed, exactly-one fallback attempt on the user-configured
 * profile with a visible switch notice, and true network cancellation.
 */
class ResilientLlmClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sse(text: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"$text\"}}\n\n" +
                "data: {\"type\":\"message_stop\"}\n\n"
        )

    private val fastSpec = RetrySpec(maxAttempts = 3, baseDelayMillis = 20, maxDelayMillis = 50)

    private fun client(
        fallbackUrl: String? = null
    ): ResilientLlmClient {
        val primaryUrl = server.url("/").toString()
        return ResilientLlmClient(
            primaryProvider = {
                ResolvedProvider(
                    name = "primary",
                    client = AnthropicLlmClient(baseUrl = primaryUrl, apiKey = "k", modelName = "m")
                )
            },
            fallbackProvider = {
                fallbackUrl?.let {
                    ResolvedProvider(
                        name = "fallback",
                        client = AnthropicLlmClient(baseUrl = it, apiKey = "k", modelName = "m")
                    )
                }
            },
            spec = fastSpec
        )
    }

    @Test
    fun `429 with retry-after is retried and then succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0").setBody("rate limited"))
        server.enqueue(sse("recovered"))

        val events = client().stream("s", listOf(LlmMessage.User("hi")), emptyList()).toList()

        assertEquals(2, server.requestCount)
        assertTrue(events.any { it is LlmEvent.TextDelta && it.delta == "recovered" })
        assertTrue(events.none { it is LlmEvent.Failed })
    }

    @Test
    fun `retries are bounded - exhausted attempts surface the failure`() = runTest {
        repeat(5) { server.enqueue(MockResponse().setResponseCode(503).setBody("down")) }

        val events = client().stream("s", listOf(LlmMessage.User("hi")), emptyList()).toList()

        assertEquals("exactly maxAttempts requests, no more", 3, server.requestCount)
        val failed = events.filterIsInstance<LlmEvent.Failed>().single()
        assertTrue(failed.error is LlmHttpException)
    }

    @Test
    fun `401 is not retried and names the configuration problem`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad key"}"""))

        val events = client().stream("s", listOf(LlmMessage.User("hi")), emptyList()).toList()

        assertEquals(1, server.requestCount)
        val failed = events.filterIsInstance<LlmEvent.Failed>().single()
        assertTrue(failed.error.message.orEmpty().contains("401"))
    }

    @Test
    fun `no second request after content was already streamed`() = runTest {
        // Inner client delivers one text delta, then the transport dies.
        // The router must NOT re-request: the retry would splice a
        // duplicated reply onto the partial one (FM-P5).
        var innerRequests = 0
        val partialThenFail = object : com.orgutil.domain.chat.LlmClient {
            override val modelName = "m"
            override fun stream(
                systemPrompt: String,
                messages: List<com.orgutil.domain.chat.LlmMessage>,
                tools: List<com.orgutil.domain.chat.AgentTool>
            ) = kotlinx.coroutines.flow.flow {
                innerRequests++
                emit(LlmEvent.TextDelta("partial"))
                emit(LlmEvent.Failed(java.io.IOException("socket reset mid-stream")))
            }
        }
        val router = ResilientLlmClient(
            primaryProvider = { ResolvedProvider("primary", partialThenFail) },
            fallbackProvider = { ResolvedProvider("fallback", partialThenFail) },
            spec = fastSpec
        )

        val events = router.stream("s", listOf(LlmMessage.User("hi")), emptyList()).toList()

        assertEquals("must not re-request after partial content", 1, innerRequests)
        assertTrue(events.any { it is LlmEvent.TextDelta && it.delta == "partial" })
        val failed = events.filterIsInstance<LlmEvent.Failed>().single()
        assertTrue(
            failed.error.message.orEmpty().contains("已收到部分内容") ||
                failed.error.message.orEmpty().contains("partial content")
        )
    }

    @Test
    fun `fallback fires exactly once after primary exhausts, with a visible switch`() = runTest {
        val fallbackServer = MockWebServer()
        fallbackServer.start()
        try {
            repeat(3) { server.enqueue(MockResponse().setResponseCode(500).setBody("primary down")) }
            fallbackServer.enqueue(sse("from fallback"))

            val events = client(fallbackUrl = fallbackServer.url("/").toString())
                .stream("s", listOf(LlmMessage.User("hi")), emptyList())
                .toList()

            assertEquals("primary exhausted its attempts", 3, server.requestCount)
            assertEquals("fallback gets exactly ONE attempt", 1, fallbackServer.requestCount)
            val switched = events.filterIsInstance<LlmEvent.ProviderSwitched>().single()
            assertEquals("fallback", switched.profileName)
            assertTrue(events.any { it is LlmEvent.TextDelta && it.delta == "from fallback" })
            assertTrue(events.none { it is LlmEvent.Failed })
        } finally {
            fallbackServer.shutdown()
        }
    }

    @Test
    fun `no fallback configured means no second provider is contacted`() = runTest {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500).setBody("down")) }

        val events = client(fallbackUrl = null).stream("s", listOf(LlmMessage.User("hi")), emptyList()).toList()

        assertEquals(3, server.requestCount)
        assertTrue(events.none { it is LlmEvent.ProviderSwitched })
        assertTrue(events.any { it is LlmEvent.Failed })
    }

    @Test
    fun `fallback failure is not retried into a third provider or loop`() = runTest {
        val fallbackServer = MockWebServer()
        fallbackServer.start()
        try {
            repeat(3) { server.enqueue(MockResponse().setResponseCode(500).setBody("down")) }
            fallbackServer.enqueue(MockResponse().setResponseCode(500).setBody("fallback down too"))

            val events = client(fallbackUrl = fallbackServer.url("/").toString())
                .stream("s", listOf(LlmMessage.User("hi")), emptyList())
                .toList()

            assertEquals(3, server.requestCount)
            assertEquals("no retry loop on the fallback", 1, fallbackServer.requestCount)
            assertTrue(events.any { it is LlmEvent.Failed })
        } finally {
            fallbackServer.shutdown()
        }
    }

    @Test
    fun `cancelling the collector truly cancels the okhttp call`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"chunk1\"}}\n\n")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )

        val seenCalls = mutableListOf<okhttp3.Call>()
        val url = server.url("/").toString()
        val resilient = ResilientLlmClient(
            primaryProvider = {
                ResolvedProvider(
                    name = "primary",
                    client = AnthropicLlmClient(baseUrl = url, apiKey = "k", modelName = "m")
                        .also { it.onCallCreated = { call -> seenCalls.add(call) } }
                )
            },
            fallbackProvider = { null },
            spec = fastSpec
        )

        val job = launch {
            resilient.stream("s", listOf(LlmMessage.User("hi")), emptyList()).toList()
        }
        withTimeout(5_000) {
            while (seenCalls.isEmpty()) kotlinx.coroutines.delay(10)
        }
        job.cancel()
        kotlinx.coroutines.delay(50)

        assertTrue("OkHttp call must be cancelled, not left reading", seenCalls.first().isCanceled())
    }
}
