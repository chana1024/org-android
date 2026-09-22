package com.orgutil.data.agent.tools

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.orgutil.data.agent.AgentPathResolver
import com.orgutil.data.datasource.DocumentTreeStore
import com.orgutil.domain.chat.ToolArgumentException
import com.orgutil.domain.chat.ToolResult
import com.orgutil.domain.model.OrgDocument
import com.orgutil.domain.model.OrgFileInfo
import com.orgutil.domain.repository.OrgFileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.IOException

/**
 * Boundary tests for the mutating write tools. These are structural path /
 * type consistency checks that hold identically in APPROVAL and AUTO modes
 * - they are not approvals, grants, or limits.
 *
 * The repository is a hand-written fake: plain Mockito matchers return
 * platform null, which Kotlin null-checks when passed to Kotlin-declared
 * interfaces. DocumentFile / Uri are Java classes and stay Mockito-mocked.
 */
class OrgCreateRenameToolsTest {

    /** Minimal recording fake; only the write path is behaviorful. */
    private class FakeOrgFileRepository : OrgFileRepository {
        val written = mutableListOf<OrgDocument>()
        var renameCall: Pair<Uri, String>? = null
        var renameResult: Result<Uri> = Result.failure(IOException("not stubbed"))

        override suspend fun getOrgFiles(uri: Uri?, query: String?, useDatabase: Boolean): Flow<List<OrgFileInfo>> =
            flowOf(emptyList())
        override suspend fun getAllOrgFiles(): List<OrgFileInfo> = emptyList()
        override suspend fun getFileInfo(uri: Uri): OrgFileInfo? = null
        override suspend fun readOrgFile(uri: Uri): Result<OrgDocument> = Result.failure(IOException("not used"))
        override suspend fun writeOrgFile(document: OrgDocument): Result<Unit> {
            written.add(document)
            return Result.success(Unit)
        }
        override suspend fun createOrgFile(name: String, content: String): Result<Uri> =
            Result.failure(IOException("not used"))
        override suspend fun deleteOrgFile(uri: Uri): Result<Unit> = Result.success(Unit)
        override suspend fun renameOrgFile(uri: Uri, newName: String): Result<Uri> {
            renameCall = uri to newName
            return renameResult
        }
        override suspend fun hasDocumentAccess(): Boolean = true
        override suspend fun requestDocumentAccess(): Boolean = true
        override suspend fun appendToCaptureFile(content: String): Result<Unit> = Result.success(Unit)
        override suspend fun getCaptureFileSize(): Long = 0L
    }

    private val treeStore = mock(DocumentTreeStore::class.java)
    private val repository = FakeOrgFileRepository()
    private val root = mock(DocumentFile::class.java)

    // Real resolver: the structural rules must run for real, not be mocked away.
    private val resolver = AgentPathResolver(treeStore, UnconfinedTestDispatcher())

    private val createTool = OrgCreateFileTool(repository, resolver)
    private val renameTool = OrgRenameFileTool(repository, resolver)

    private fun mockUri(value: String): Uri = mock(Uri::class.java).also {
        `when`(it.toString()).thenReturn(value)
    }

    @Before
    fun setup() {
        `when`(treeStore.requireTreeDocumentFile()).thenReturn(root)
        `when`(root.exists()).thenReturn(true)
        `when`(root.isDirectory).thenReturn(true)
    }

    private fun args(vararg pairs: Pair<String, String>) = kotlinx.serialization.json.buildJsonObject {
        pairs.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) }
    }

    @Test
    fun `create succeeds with an omitted org extension at the root`() = runTest(UnconfinedTestDispatcher()) {
        `when`(root.findFile("shopping.org")).thenReturn(null)
        val createdUri = mockUri("content://created/shopping.org")
        val created = mock(DocumentFile::class.java)
        `when`(created.uri).thenReturn(createdUri)
        `when`(root.createFile("text/org", "shopping.org")).thenReturn(created)

        val result = createTool.execute(args("path" to "shopping", "content" to "* list"))

        assertTrue(result is ToolResult.Ok)
        val doc = repository.written.single()
        assertEquals("shopping.org", doc.fileName)
        assertEquals("* list", doc.content)
        assertEquals(createdUri, doc.uri)
    }

    @Test
    fun `create succeeds with an omitted extension in a nested directory`() = runTest(UnconfinedTestDispatcher()) {
        val notes = mock(DocumentFile::class.java)
        `when`(root.findFile("notes")).thenReturn(notes)
        `when`(notes.exists()).thenReturn(true)
        `when`(notes.isDirectory).thenReturn(true)
        `when`(notes.findFile("daily.org")).thenReturn(null)
        val createdUri = mockUri("content://created/daily.org")
        val created = mock(DocumentFile::class.java)
        `when`(created.uri).thenReturn(createdUri)
        `when`(notes.createFile("text/org", "daily.org")).thenReturn(created)

        val result = createTool.execute(args("path" to "notes/daily", "content" to "* today"))

        assertTrue(result is ToolResult.Ok)
        assertEquals("daily.org", repository.written.single().fileName)
    }

    @Test
    fun `create keeps an explicit org_archive suffix`() = runTest(UnconfinedTestDispatcher()) {
        `when`(root.findFile("arch.org_archive")).thenReturn(null)
        val archiveUri = mockUri("content://created/arch.org_archive")
        val created = mock(DocumentFile::class.java)
        `when`(created.uri).thenReturn(archiveUri)
        `when`(root.createFile("text/org", "arch.org_archive")).thenReturn(created)

        val result = createTool.execute(args("path" to "arch.org_archive", "content" to ""))

        assertTrue(result is ToolResult.Ok)
        assertEquals("arch.org_archive", repository.written.single().fileName)
    }

    @Test
    fun `create rejects illegal paths before touching the provider`() = runTest(UnconfinedTestDispatcher()) {
        for (bad in listOf("../evil.org", "notes/.secret", ".git/config.org", ".orgutil_favorites", "notes/readme")) {
            try {
                createTool.execute(args("path" to bad, "content" to "x"))
                fail("expected rejection: $bad")
            } catch (expected: ToolArgumentException) {
            }
        }
        assertTrue(repository.written.isEmpty())
    }

    @Test
    fun `create reports an error when the file already exists`() = runTest(UnconfinedTestDispatcher()) {
        `when`(root.findFile("shopping.org")).thenReturn(mock(DocumentFile::class.java))

        val result = createTool.execute(args("path" to "shopping", "content" to "x"))

        assertTrue(result is ToolResult.Error)
        assertTrue(repository.written.isEmpty())
    }

    @Test
    fun `rename rejects bad new names without calling the repository`() = runTest(UnconfinedTestDispatcher()) {
        for (bad in listOf("noext", "sub/new.org", ".hidden.org", ".gitignore.org", ".orgutil_favorites.org")) {
            val result = renameTool.execute(args("path" to "old.org", "newName" to bad))
            assertTrue("expected error for '$bad'", result is ToolResult.Error)
        }
        assertEquals(null, repository.renameCall)
    }

    @Test
    fun `rename succeeds and reports the real new URI`() = runTest(UnconfinedTestDispatcher()) {
        val notes = mock(DocumentFile::class.java)
        val oldFile = mock(DocumentFile::class.java)
        val oldUri = mockUri("content://tree/notes/old.org")
        val newUri = mockUri("content://tree/notes/renamed.org")
        `when`(root.findFile("notes")).thenReturn(notes)
        `when`(notes.exists()).thenReturn(true)
        `when`(notes.isDirectory).thenReturn(true)
        `when`(notes.findFile("old.org")).thenReturn(oldFile)
        `when`(oldFile.exists()).thenReturn(true)
        `when`(oldFile.isFile).thenReturn(true)
        `when`(oldFile.uri).thenReturn(oldUri)
        repository.renameResult = Result.success(newUri)

        val result = renameTool.execute(args("path" to "notes/old.org", "newName" to "renamed.org"))

        assertTrue(result is ToolResult.Ok)
        assertEquals(oldUri, repository.renameCall?.first)
        assertEquals("renamed.org", repository.renameCall?.second)
        val ok = result as ToolResult.Ok
        assertTrue(ok.summaryForModel.contains("renamed.org"))
        assertTrue(ok.summaryForModel.contains("content://tree/notes/renamed.org"))
        assertEquals(listOf("notes/old.org", "notes/renamed.org"), ok.affectedPaths)
    }

    @Test
    fun `rename failure surfaces as an error result`() = runTest(UnconfinedTestDispatcher()) {
        val oldFile = mock(DocumentFile::class.java)
        val oldUri = mockUri("content://old.org")
        `when`(root.findFile("old.org")).thenReturn(oldFile)
        `when`(oldFile.exists()).thenReturn(true)
        `when`(oldFile.isFile).thenReturn(true)
        `when`(oldFile.uri).thenReturn(oldUri)
        repository.renameResult = Result.failure(IOException("provider refused"))

        val result = renameTool.execute(args("path" to "old.org", "newName" to "renamed.org"))

        assertTrue(result is ToolResult.Error)
    }
}
