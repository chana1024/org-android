package com.orgutil.data.agent

import com.orgutil.domain.chat.LlmEvent
import com.orgutil.domain.chat.LlmMessage
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the JSON-null boundary of both SSE parsers (NM-1..NM-7).
 *
 * DeepSeek-style gateways send explicit `"content": null` inside tool-call
 * frames. kotlinx.serialization's JsonNull IS a JsonPrimitive whose .content
 * is the literal string "null", so the naive `?.jsonPrimitive?.content`
 * accessors inject "null" into assistant text and tool-call accumulators
 * (user-visible as "null null null…" replies, and corrupted tool args).
 * Boundary rule under test: only genuine string primitives pass; absent,
 * JSON null, and non-string values yield nothing - while a REAL string
 * "null" from the model must survive verbatim.
 */
class LlmClientNullParsingTest {

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

    private fun sse(vararg events: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")

    private suspend fun openAi(vararg events: String): List<LlmEvent> {
        server.enqueue(sse(*events))
        return OpenAiCompatLlmClient(
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        apiKey = "test-key",
        modelName = "deepseek-chat"
        ).stream("sys", listOf(LlmMessage.User("hi")), emptyList()).toList()
    }

    /** NM-1 + NM-4: JsonNull content ignored; real string "null" preserved. */
    @Test
    fun `openai content null emits nothing while real string null survives`() = runTest {
        val events = openAi(
            """{"choices":[{"index":0,"delta":{"role":"assistant","content":null}}]}""",
            """{"choices":[{"index":0,"delta":{"content":"OK"}}]}""",
            """{"choices":[{"index":0,"delta":{"content":null}}]}""",
            """{"choices":[{"index":0,"delta":{"content":" null"}}]}""",
            """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
        )

        val deltas = events.filterIsInstance<LlmEvent.TextDelta>()
        assertEquals(listOf("OK", " null"), deltas.map { it.delta })
        assertEquals("OK null", events.filterIsInstance<LlmEvent.TurnCompleted>().single().text)
    }

    /** NM-2 + NM-3: null tool_call id/name/arguments must not poison accumulators. */
    @Test
    fun `openai tool_call null id name arguments keep accumulators clean`() = runTest {
        val events = openAi(
            """{"choices":[{"index":0,"delta":{"content":null,"tool_calls":[{"index":0,"id":null,"type":"function","function":{"name":null,"arguments":null}}]}}]}""",
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_x","function":{"name":"org_search","arguments":"{\"query\":\"deep"}}]}}]}""",
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":null,"function":{"name":null,"arguments":"seek\"}"}}]}}]}""",
            """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""
        )

        assertEquals(0, events.filterIsInstance<LlmEvent.TextDelta>().size)
        val uses = events.filterIsInstance<LlmEvent.ToolUseArrived>().map { it.block }
        assertEquals(1, uses.size)
        assertEquals("call_x", uses[0].id)
        assertEquals("org_search", uses[0].name)
        assertEquals("deepseek", uses[0].args["query"]?.jsonPrimitive?.content)
    }

    /** NM-6: absent/empty content, reasoning frames and finish frames emit no text. */
    @Test
    fun `openai absent empty and reasoning frames emit no text`() = runTest {
        val events = openAi(
            """{"choices":[{"index":0,"delta":{"role":"assistant"}}]}""",
            """{"choices":[{"index":0,"delta":{"content":""}}]}""",
            """{"choices":[{"index":0,"delta":{"reasoning_content":"thinking…","content":null}}]}""",
            """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
        )

        assertEquals(0, events.filterIsInstance<LlmEvent.TextDelta>().size)
        assertEquals("", events.filterIsInstance<LlmEvent.TurnCompleted>().single().text)
    }

    /** NM-5: non-string content is not coerced into text. */
    @Test
    fun `openai non-string content is ignored`() = runTest {
        val events = openAi(
            """{"choices":[{"index":0,"delta":{"content":42}}]}""",
            """{"choices":[{"index":0,"delta":{"content":"ans"}}]}""",
            """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
        )

        assertEquals(listOf("ans"), events.filterIsInstance<LlmEvent.TextDelta>().map { it.delta })
    }

    /** NM-7: Anthropic text_delta with null text must not append "null". */
    @Test
    fun `anthropic text_delta null text does not emit null`() = runTest {
        server.enqueue(
            sse(
                """{"type":"message_start","message":{}}""",
                """{"type":"content_block_start","index":0,"content_block":{"type":"text"}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":null}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"OK"}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":null}}""",
                """{"type":"content_block_stop","index":0}""",
                """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
                """{"type":"message_stop"}"""
            )
        )
        val events = AnthropicLlmClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "test-key",
            modelName = "test-model"
        ).stream("sys", listOf(LlmMessage.User("hi")), emptyList()).toList()

        assertEquals(listOf("OK"), events.filterIsInstance<LlmEvent.TextDelta>().map { it.delta })
        assertEquals("OK", events.filterIsInstance<LlmEvent.TurnCompleted>().single().text)
    }

    /** NM-7: Anthropic tool_use with null id/name and null partial_json stays clean. */
    @Test
    fun `anthropic tool_use null fields stay clean`() = runTest {
        server.enqueue(
            sse(
                """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":null,"name":null}}""",
                """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"org_search"}}""",
                """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":null}}""",
                """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"query\":"}}""",
                """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"x\"}"}}""",
                """{"type":"content_block_stop","index":0}""",
                """{"type":"content_block_stop","index":1}""",
                """{"type":"message_stop"}"""
            )
        )
        val events = AnthropicLlmClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "test-key",
            modelName = "test-model"
        ).stream("sys", listOf(LlmMessage.User("hi")), emptyList()).toList()

        val uses = events.filterIsInstance<LlmEvent.ToolUseArrived>().map { it.block }
        assertEquals(2, uses.size)
        assertEquals("", uses[0].id)
        assertEquals("", uses[0].name)
        assertEquals("toolu_1", uses[1].id)
        assertEquals("org_search", uses[1].name)
        assertEquals("x", uses[1].args["query"]?.jsonPrimitive?.content)
        assertTrue(events.filterIsInstance<LlmEvent.TextDelta>().isEmpty())
    }
}
