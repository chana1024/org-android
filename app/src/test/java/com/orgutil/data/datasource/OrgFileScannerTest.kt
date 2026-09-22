package com.orgutil.data.datasource

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

/**
 * Decision A: FILE_LIST search must match file names / relative paths only
 * and must NEVER read file bodies (no openInputStream on the search path).
 */
class OrgFileScannerTest {

    @Mock lateinit var context: Context
    @Mock lateinit var documentTreeStore: DocumentTreeStore
    @Mock lateinit var contentResolver: ContentResolver

    private val treeUri: Uri = mock(Uri::class.java)

    private lateinit var scanner: OrgFileScanner

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        `when`(context.contentResolver).thenReturn(contentResolver)
        `when`(documentTreeStore.getStoredTreeUri()).thenReturn(treeUri)
        // Unconfined keeps withContext(ioDispatcher) on the test thread, where
        // the thread-local mockStatic registration is visible.
        scanner = OrgFileScanner(context, documentTreeStore, UnconfinedTestDispatcher())
    }

    @Test
    fun `file list search matches file names without reading content`() = runTest {
        val root = rootDocument(listOf(directoryDocument("docs", listOf(fileDocument("shopping.org")))))
        mockStatic(DocumentFile::class.java).use { mockedStatic ->
            mockedStatic.`when`<DocumentFile> { DocumentFile.fromTreeUri(context, treeUri) }
                .thenReturn(root)

            val results = scanner.getOrgFiles(treeUri, "shopping")
            assertEquals(1, results.size)
            assertEquals("docs/shopping.org", results[0].name)
        }
        verify(contentResolver, never()).openInputStream(any<Uri>())
    }

    @Test
    fun `file list search matches relative paths without reading content`() = runTest {
        val root = rootDocument(listOf(directoryDocument("docs", listOf(fileDocument("daily.org")))))
        mockStatic(DocumentFile::class.java).use { mockedStatic ->
            mockedStatic.`when`<DocumentFile> { DocumentFile.fromTreeUri(context, treeUri) }
                .thenReturn(root)

            val results = scanner.getOrgFiles(treeUri, "docs")
            assertEquals(1, results.size)
            assertEquals("docs/daily.org", results[0].name)
        }
        verify(contentResolver, never()).openInputStream(any<Uri>())
    }

    @Test
    fun `file list search does not fall back to content matching`() = runTest {
        val root = rootDocument(listOf(fileDocument("unrelated.org")))
        mockStatic(DocumentFile::class.java).use { mockedStatic ->
            mockedStatic.`when`<DocumentFile> { DocumentFile.fromTreeUri(context, treeUri) }
                .thenReturn(root)

            val results = scanner.getOrgFiles(treeUri, "garden")
            // "garden" appears only in the (unread) body - the old behavior
            // opened the file and matched; name/path semantics must not.
            assertTrue(results.isEmpty())
        }
        verify(contentResolver, never()).openInputStream(any<Uri>())
    }

    @Test
    fun `file list search without query lists entries without reading content`() = runTest {
        val root = rootDocument(listOf(fileDocument("a.org")))
        mockStatic(DocumentFile::class.java).use { mockedStatic ->
            mockedStatic.`when`<DocumentFile> { DocumentFile.fromTreeUri(context, treeUri) }
                .thenReturn(root)

            val results = scanner.getOrgFiles(treeUri, null)
            assertEquals(1, results.size)
        }
        verify(contentResolver, never()).openInputStream(any<Uri>())
    }

    // --- DocumentFile tree fakes -------------------------------------------------

    private fun rootDocument(children: List<DocumentFile>): DocumentFile {
        val root = mock(DocumentFile::class.java)
        `when`(root.exists()).thenReturn(true)
        `when`(root.isDirectory).thenReturn(true)
        `when`(root.listFiles()).thenReturn(children.toTypedArray())
        return root
    }

    private fun directoryDocument(name: String, children: List<DocumentFile>): DocumentFile {
        val dir = mock(DocumentFile::class.java)
        `when`(dir.name).thenReturn(name)
        `when`(dir.isDirectory).thenReturn(true)
        `when`(dir.isFile).thenReturn(false)
        `when`(dir.listFiles()).thenReturn(children.toTypedArray())
        return dir
    }

    private fun fileDocument(name: String): DocumentFile {
        val file = mock(DocumentFile::class.java)
        val uri = mock(Uri::class.java)
        `when`(uri.toString()).thenReturn("content://tree/test/document/$name")
        `when`(file.name).thenReturn(name)
        `when`(file.isDirectory).thenReturn(false)
        `when`(file.isFile).thenReturn(true)
        `when`(file.uri).thenReturn(uri)
        `when`(file.lastModified()).thenReturn(1L)
        `when`(file.length()).thenReturn(10L)
        return file
    }
}
