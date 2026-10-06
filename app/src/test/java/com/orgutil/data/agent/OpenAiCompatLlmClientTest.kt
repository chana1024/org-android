package com.orgutil.data.agent

import com.orgutil.domain.chat.AgentTool
import com.orgutil.domain.chat.LlmEvent
import com.orgutil.domain.chat.LlmMessage
import com.orgutil.domain.chat.ToolPolicy
import com.orgutil.domain.chat.ToolResultBlock
import com.orgutil.domain.chat.ToolUseBlock
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins FM-P1/P2 against a real loopback HTTP endpoint speaking the
 * OpenAI-compatible Chat Completions SSE protocol:
 * - tool_calls argument JSON arrives as string DELTAS and must be merged
 *   per index without cross-talk between parallel calls;
 * - the request body must follow the protocol: system role, tools[]
 *   function wrapper, assistant tool_calls array, role=tool results with
 *   matching tool_call_id.
 */
class OpenAiCompatLlmClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OpenAiCompatLlmClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OpenAiCompatLlmClient(
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            apiKey = "test-key",
            modelName = "test-model"
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sse(vararg events: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")

    private val tool = object : AgentTool {
        override val name = "org_list_files"
        override val description = "list files"
        override val parametersSchema = kotlinx.serialization.json.buildJsonObject {
            put("type", "object")
            put("properties", kotlinx.serialization.json.buildJsonObject {
                put("path", kotlinx.serialization.json.buildJsonObject { put("type", "string") })
            })
        }
        override val policy = ToolPolicy(com.orgutil.domain.chat.RiskLevel.LOW, sessionGrantAllowed = false)
        override fun describeArgs(args: kotlinx.serialization.json.JsonObject) = ""
        override suspend fun execute(args: kotlinx.serialization.json.JsonObject) =
            com.orgutil.domain.chat.ToolResult.Ok("ok")
    }

    @Test
    fun `text deltas concatenate and split tool_call argument deltas merge per index`() = runTest {
        server.enqueue(
            sse(
                """{"choices":[{"index":0,"delta":{"role":"assistant","content":"He"}}]}""",
                """{"choices":[{"index":0,"delta":{"content":"llo"}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"org_list_files","arguments":"{\"pa"}}]}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"th\":\"gtd.org\"}"}}]}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"call_b","type":"function","function":{"name":"org_list_files","arguments":"{}"}}]}}]}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""
            )
        )

        val events = client.stream("sys", listOf(LlmMessage.User("hi")), listOf(tool)).toList()

        val text = events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.delta }
        assertEquals("Hello", text)

        val uses = events.filterIsInstance<LlmEvent.ToolUseArrived>().map { it.block }
        assertEquals(2, uses.size)
        assertEquals("call_a", uses[0].id)
        assertEquals("org_list_files", uses[0].name)
        assertEquals("gtd.org", uses[0].args["path"]?.jsonPrimitive?.content)
        assertEquals("call_b", uses[1].id)

        val completed = events.filterIsInstance<LlmEvent.TurnCompleted>().single()
        assertEquals(2, completed.toolUseCount)
        assertEquals("Hello", completed.text)
    }

    @Test
    fun `non-2xx surfaces as LlmHttpException with status`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))

        val events = client.stream("sys", listOf(LlmMessage.User("hi")), emptyList()).toList()

        val failed = events.filterIsInstance<LlmEvent.Failed>().single()
        assertTrue(failed.error is LlmHttpException)
        assertEquals(401, (failed.error as LlmHttpException).code)
    }

    @Test
    fun `request body follows chat completions protocol with tools and tool results`() = runTest {
        server.enqueue(
            sse("""{"choices":[{"index":0,"delta":{"content":"done"},"finish_reason":"stop"}]}""")
        )

        client.stream(
            systemPrompt = "be brief",
            messages = listOf(
                LlmMessage.User("list files"),
                LlmMessage.Assistant(
                    "checking",
                    listOf(ToolUseBlock("call_1", "org_list_files", Json.parseToJsonElement("""{"path":"gtd.org"}""").jsonObject))
                ),
                LlmMessage.ToolResults(listOf(ToolResultBlock("call_1", "org_list_files", "3 files", isError = false)))
            ),
            tools = listOf(tool)
        ).toList()

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))

        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("test-model", body["model"]?.jsonPrimitive?.content)
        assertEquals(true, body["stream"]?.jsonPrimitive?.content?.toBoolean())

        val messageList = body["messages"].toString()
        assertTrue(messageList.contains("\"role\":\"system\""))
        assertTrue(messageList.contains("be brief"))
        assertTrue(messageList.contains("\"role\":\"tool\""))
        assertTrue(messageList.contains("call_1"))
        assertTrue(messageList.contains("3 files"))
        // Assistant turn must carry the tool_calls array for the tool results.
        assertTrue(messageList.contains("tool_calls"))
        assertTrue(messageList.contains("\"arguments\":\"{\\\"path\\\":\\\"gtd.org\\\"}\""))

        val tools = body["tools"].toString()
        assertTrue(tools.contains("\"type\":\"function\""))
        assertTrue(tools.contains("org_list_files"))
        assertTrue(tools.contains("parameters"))
    }

    @Test
    fun `base url variants resolve to the chat completions path`() {
        fun resolve(baseUrl: String) =
            OpenAiCompatLlmClient(baseUrl, "k", "m").resolveUrlForTest(baseUrl)

        assertEquals("https://gw.example.com/v1/chat/completions", resolve("https://gw.example.com"))
        assertEquals("https://gw.example.com/v1/chat/completions", resolve("https://gw.example.com/v1"))
        assertEquals("https://gw.example.com/v1/chat/completions", resolve("https://gw.example.com/v1/"))
        assertEquals(
            "https://gw.example.com/custom/chat/completions",
            resolve("https://gw.example.com/custom/chat/completions")
        )
    }
}
