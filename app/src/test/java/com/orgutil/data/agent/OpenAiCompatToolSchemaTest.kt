package com.orgutil.data.agent

import android.content.Context
import com.orgutil.data.agent.tools.AgentToolRegistry
import com.orgutil.data.agent.tools.GitSyncTool
import com.orgutil.data.agent.tools.OrgArchiveDoneTool
import com.orgutil.data.agent.tools.OrgIntegrateTool
import com.orgutil.data.agent.tools.OrgCreateFileTool
import com.orgutil.data.agent.tools.OrgDeleteFileTool
import com.orgutil.data.agent.tools.OrgListFilesTool
import com.orgutil.data.agent.tools.OrgParseOutlineTool
import com.orgutil.data.agent.tools.OrgReadFileTool
import com.orgutil.data.agent.tools.OrgRenameFileTool
import com.orgutil.data.agent.tools.OrgSearchTool
import com.orgutil.data.agent.tools.OrgWriteFileTool
import com.orgutil.data.datasource.DocumentTreeStore
import com.orgutil.data.repository.GtdArchiveService
import com.orgutil.data.repository.OrgIntegrationService
import com.orgutil.data.repository.SkillRunStore
import com.orgutil.domain.chat.LlmEvent
import com.orgutil.domain.chat.LlmMessage
import com.orgutil.domain.model.OrgFileInfo
import com.orgutil.domain.repository.GitSyncRepository
import com.orgutil.domain.repository.OrgFileRepository
import com.orgutil.domain.sync.GitRepoSnapshot
import com.orgutil.domain.sync.GitSyncOutcome
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.File

/**
 * Pins the DeepSeek HTTP-400 class of failure against the REAL registered
 * tool set over real loopback HTTP:
 *
 * DeepSeek (OpenAI-compatible Chat Completions) rejects a request when any
 * tools[].function.parameters is not a JSON Schema of type "object" -
 * "Invalid schema for function org_list_files: schema must be JSON Schema
 * type object, got type null." OrgListFilesTool / GitSyncTool declare an
 * EMPTY schema object (no "type"), and OpenAiCompatLlmClient serializes it
 * verbatim. The validator below mirrors that gateway gate; these tests are
 * the red/green harness for the normalization fix and guard the parameterized
 * tools' type/properties/required against regressions (FM-1/2/3/5/7).
 */
class OpenAiCompatToolSchemaTest {

    /** Mirrors DeepSeek's function-schema gate: any violation -> 400. */
    private class DeepSeekSchemaValidator : Dispatcher() {
        var lastViolation: String? = null

        override fun dispatch(request: RecordedRequest): MockResponse {
            val body = runCatching {
                // clone(): the recorded body must stay readable for takeRequest() assertions
                Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject
            }.getOrNull() ?: return badRequest("unparseable request body")
            val tools = body["tools"]?.jsonArray ?: return ok()
            tools.forEach { element ->
                val function = element.jsonObject["function"]?.jsonObject ?: return@forEach
                val name = function["name"]?.jsonPrimitive?.content ?: "?"
                val params = function["parameters"] as? JsonObject
                    ?: return badRequest(
                        "Invalid schema for function $name: schema must be JSON Schema type object, got type null."
                    )
                val type = (params["type"] as? JsonPrimitive)?.content
                if (type != "object") {
                    return badRequest(
                        "Invalid schema for function $name: schema must be JSON Schema type object, got type ${type ?: "null"}."
                    )
                }
                val properties = params["properties"] as? JsonObject
                    ?: return badRequest("Invalid schema for function $name: properties must be an object.")
                params["required"]?.let { required ->
                    if (required !is JsonArray || required.any { it !is JsonPrimitive || !it.isString }) {
                        return badRequest("Invalid schema for function $name: required must be an array of strings.")
                    }
                    val unknown = required.map { it.jsonPrimitive.content }.filter { it !in properties.keys }
                    if (unknown.isNotEmpty()) {
                        return badRequest("Invalid schema for function $name: required lists unknown properties $unknown.")
                    }
                }
            }
            return ok()
        }

        private fun ok() = MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"SCHEMA-OK\"},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n"
            )

        private fun badRequest(message: String): MockResponse {
            lastViolation = message
            return MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"error":{"message":"$message","type":"invalid_request_error","code":"invalid_request_error"}}"""
                )
        }
    }

    private class FakeOrgFileRepository : OrgFileRepository {
        override suspend fun getOrgFiles(uri: Uri?, query: String?, useDatabase: Boolean): Flow<List<OrgFileInfo>> =
            flowOf(emptyList())
        override suspend fun getAllOrgFiles(): List<OrgFileInfo> = emptyList()
        override suspend fun getFileInfo(uri: Uri): OrgFileInfo? = null
        override suspend fun readOrgFile(uri: Uri): Result<com.orgutil.domain.model.OrgDocument> =
            Result.failure(IllegalStateException("not used"))
        override suspend fun writeOrgFile(document: com.orgutil.domain.model.OrgDocument): Result<Unit> =
            Result.success(Unit)
        override suspend fun createOrgFile(name: String, content: String): Result<Uri> =
            Result.failure(IllegalStateException("not used"))
        override suspend fun deleteOrgFile(uri: Uri): Result<Unit> = Result.success(Unit)
        override suspend fun renameOrgFile(uri: Uri, newName: String): Result<Uri> =
            Result.failure(IllegalStateException("not used"))
        override suspend fun hasDocumentAccess(): Boolean = true
        override suspend fun requestDocumentAccess(): Boolean = true
        override suspend fun appendToCaptureFile(content: String): Result<Unit> = Result.success(Unit)
        override suspend fun getCaptureFileSize(): Long = 0L
    }

    private class FakeGitSyncRepository : GitSyncRepository {
        override suspend fun runSync(): Result<GitSyncOutcome> = Result.failure(IllegalStateException("not used"))
        override suspend fun testConnection(): Result<Unit> = Result.success(Unit)
        override suspend fun getRepoSnapshot(): GitRepoSnapshot? = null
        override suspend fun resolveRepoRoot(): Result<File> = Result.failure(IllegalStateException("not used"))
        override fun getRemoteUrl(): String? = null
        override fun saveRemoteUrl(url: String) = Unit
        override fun getRepoRootOverride(): String? = null
        override fun saveRepoRootOverride(path: String) = Unit
        override fun isAutoSyncEnabled(): Boolean = false
        override fun setAutoSyncEnabled(enabled: Boolean) = Unit
        override fun getLastSyncTime(): Long = 0L
        override fun getCredentials(): Pair<String, String>? = null
        override fun saveCredentials(username: String, token: String) = Unit
    }

    private lateinit var server: MockWebServer
    private lateinit var validator: DeepSeekSchemaValidator

    /** The exact tool list the app registers - real classes, minimal fakes. */
    private fun noSkillRun() = SkillRunStore { emptyList() }

    private fun realRegistryTools() = AgentToolRegistry(
        listFiles = OrgListFilesTool(FakeOrgFileRepository()),
        search = OrgSearchTool(FakeOrgFileRepository()),
        readFile = OrgReadFileTool(FakeOrgFileRepository(), realResolver()),
        parseOutline = OrgParseOutlineTool(FakeOrgFileRepository(), realResolver()),
        writeFile = OrgWriteFileTool(FakeOrgFileRepository(), realResolver(), noSkillRun()),
        createFile = OrgCreateFileTool(FakeOrgFileRepository(), realResolver(), noSkillRun()),
        deleteFile = OrgDeleteFileTool(FakeOrgFileRepository(), realResolver(), noSkillRun()),
        renameFile = OrgRenameFileTool(FakeOrgFileRepository(), realResolver(), noSkillRun()),
        archiveDone = OrgArchiveDoneTool(
            GtdArchiveService(mock(Context::class.java), mock(DocumentTreeStore::class.java), FakeOrgFileRepository()),
            noSkillRun()
        ),
        gitSync = GitSyncTool(FakeGitSyncRepository()),
        orgIntegrate = OrgIntegrateTool(
            OrgIntegrationService(
                mock(Context::class.java), mock(DocumentTreeStore::class.java),
                FakeOrgFileRepository(), realResolver()
            ),
            noSkillRun()
        )
    ).tools

    private fun realResolver() =
        com.orgutil.data.agent.AgentPathResolver(mock(DocumentTreeStore::class.java), UnconfinedTestDispatcher())

    @Before
    fun setUp() {
        validator = DeepSeekSchemaValidator()
        server = MockWebServer()
        server.dispatcher = validator
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun openAiClient() = OpenAiCompatLlmClient(
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        apiKey = "test-key",
        modelName = "deepseek-chat"
    )

    /**
     * FM-1/FM-2: the full registered tool set must clear DeepSeek's schema
     * gate; today org_list_files/git_sync send parameters {} and the gateway
     * answers 400 with the exact reported message.
     */
    @Test
    fun `all registered tool schemas pass a DeepSeek-style validator and the turn completes`() = runTest {
        val events = openAiClient()
            .stream("sys", listOf(LlmMessage.User("hi")), realRegistryTools())
            .toList()

        val failed = events.filterIsInstance<LlmEvent.Failed>().firstOrNull()
        assertNull("DeepSeek-style gateway rejected the request: ${(failed?.error as? LlmHttpException)?.bodySnippet ?: failed?.error?.message}", failed)
        assertEquals("SCHEMA-OK", events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.delta })
    }

    /**
     * FM-1/2/3/5: serialized shape per tool. type=object + properties for all
     * nine; parameterless tools get empty properties and no required;
     * parameterized tools keep their exact required arrays and property
     * types/descriptions.
     */
    @Test
    fun `serialized parameters keep type object properties and required for every registered tool`() = runTest {
        openAiClient().stream("sys", listOf(LlmMessage.User("hi")), realRegistryTools()).toList()

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val functions = body["tools"]!!.jsonArray.associateBy { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }
            .mapValues { it.value.jsonObject["function"]!!.jsonObject }

        assertEquals(
            setOf(
                "org_list_files", "org_search", "org_read_file", "org_parse_outline",
                "org_write_file", "org_create_file", "org_delete_file", "org_rename_file", "git_sync"
            ),
            functions.keys
        )
        functions.forEach { (name, function) ->
            val params = function["parameters"] as? JsonObject
            assertNotNull("parameters missing for $name", params)
            assertEquals("type for $name", "object", params!!["type"]?.jsonPrimitive?.content)
            assertTrue("properties must be an object for $name", params["properties"] is JsonObject)
            val required = params["required"] as? JsonArray
            required?.forEach { entry ->
                assertTrue("required entries must be strings for $name", entry is JsonPrimitive && entry.isString)
                assertTrue(
                    "required must name declared properties for $name",
                    entry.jsonPrimitive.content in (params["properties"] as JsonObject).keys
                )
            }
        }

        fun paramsOf(name: String): JsonObject = functions.getValue(name)["parameters"]!!.jsonObject
        fun requiredOf(name: String) = paramsOf(name)["required"]?.jsonArray?.map { it.jsonPrimitive.content }

        // Parameterless tools: empty properties, no required key.
        listOf("org_list_files", "git_sync").forEach { name ->
            assertEquals("properties of $name must be empty", 0, (paramsOf(name)["properties"] as JsonObject).size)
            assertNull("required must be absent for $name", paramsOf(name)["required"])
        }

        // Parameterized tools: exact required order and property shapes preserved.
        assertEquals(listOf("query"), requiredOf("org_search"))
        assertEquals(listOf("path"), requiredOf("org_read_file"))
        assertEquals(listOf("path"), requiredOf("org_parse_outline"))
        assertEquals(listOf("path", "content"), requiredOf("org_write_file"))
        assertEquals(listOf("path", "content"), requiredOf("org_create_file"))
        assertEquals(listOf("path"), requiredOf("org_delete_file"))
        assertEquals(listOf("path", "newName"), requiredOf("org_rename_file"))

        val readPath = paramsOf("org_read_file")["properties"]!!.jsonObject["path"]!!.jsonObject
        assertEquals("string", readPath["type"]?.jsonPrimitive?.content)
        assertEquals("Relative path, e.g. notes/gtd.org", readPath["description"]?.jsonPrimitive?.content)

        val renameNewName = paramsOf("org_rename_file")["properties"]!!.jsonObject["newName"]!!.jsonObject
        assertEquals("string", renameNewName["type"]?.jsonPrimitive?.content)
        assertTrue(
            "description must survive normalization",
            renameNewName["description"]!!.jsonPrimitive.content.contains("New file name")
        )
    }

    /**
     * FM-7: the Anthropic path already pads schemas; whatever normalization is
     * shared must keep input_schema a type=object schema with properties and
     * the parameterized tools' required arrays intact.
     */
    @Test
    fun `anthropic input_schema stays a valid object schema for every registered tool`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(401).setBody("""{"type":"error","error":{"type":"authentication_error","message":"test"}}""")
        }
        AnthropicLlmClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "test-key",
            modelName = "test-model"
        ).stream("sys", listOf(LlmMessage.User("hi")), realRegistryTools()).toList()

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val tools = body["tools"]!!.jsonArray
        assertEquals(9, tools.size)
        tools.forEach { element ->
            val tool = element.jsonObject
            val name = tool["name"]!!.jsonPrimitive.content
            val schema = tool["input_schema"] as? JsonObject
            assertNotNull("input_schema missing for $name", schema)
            assertEquals("type for $name", "object", schema!!["type"]?.jsonPrimitive?.content)
            assertTrue("properties must be an object for $name", schema["properties"] is JsonObject)
        }
        val search = tools.first { it.jsonObject["name"]!!.jsonPrimitive.content == "org_search" }.jsonObject
        assertEquals(
            listOf("query"),
            search["input_schema"]!!.jsonObject["required"]?.jsonArray?.map { it.jsonPrimitive.content }
        )
    }
}
