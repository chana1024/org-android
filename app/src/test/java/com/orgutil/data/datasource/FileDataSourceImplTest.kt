package com.orgutil.data.datasource

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.MockitoAnnotations

/**
 * renameFile MUST go through DocumentsContract.renameDocument and return the
 * URI it hands back: SAF document URIs embed the display name, so continuing
 * with the old URI after a rename dangles. Also, with documentfile 1.0.1,
 * DocumentFile.fromSingleUri(...).renameTo throws UnsupportedOperationException,
 * so the DocumentFile helper path is not usable here at all.
 */
class FileDataSourceImplTest {

    @Mock lateinit var context: Context
    @Mock lateinit var documentTreeStore: DocumentTreeStore
    @Mock lateinit var contentResolver: ContentResolver

    private lateinit var dataSource: FileDataSourceImpl

    private val oldUri: Uri = mock(Uri::class.java)
    private val newUri: Uri = mock(Uri::class.java)

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        `when`(context.contentResolver).thenReturn(contentResolver)
        // Unconfined keeps withContext(ioDispatcher) on the test thread, where
        // the thread-local mockStatic registration is visible.
        dataSource = FileDataSourceImpl(context, documentTreeStore, mock(OrgInboxStore::class.java), mock(OrgFileScanner::class.java), ioDispatcher = UnconfinedTestDispatcher())
    }

    @Test
    fun `renameFile returns the new URI from DocumentsContract`() = runTest(UnconfinedTestDispatcher()) {
        mockStatic(DocumentsContract::class.java).use { mocked ->
            mocked.`when`<Uri> { DocumentsContract.renameDocument(any(), eq(oldUri), eq("renamed.org")) }
                .thenReturn(newUri)

            assertEquals(newUri, dataSource.renameFile(oldUri, "renamed.org"))
        }
    }

    @Test
    fun `null result means an in-place rename and the old URI stays valid`() = runTest(UnconfinedTestDispatcher()) {
        mockStatic(DocumentsContract::class.java).use { mocked ->
            mocked.`when`<Uri> { DocumentsContract.renameDocument(any(), eq(oldUri), eq("renamed.org")) }
                .thenReturn(null)

            assertEquals(oldUri, dataSource.renameFile(oldUri, "renamed.org"))
        }
    }

    @Test
    fun `rename failures are wrapped in IOException`() = runTest(UnconfinedTestDispatcher()) {
        mockStatic(DocumentsContract::class.java).use { mocked ->
            mocked.`when`<Uri> { DocumentsContract.renameDocument(any(), eq(oldUri), eq("renamed.org")) }
                .thenThrow(java.io.FileNotFoundException("gone"))

            try {
                kotlinx.coroutines.runBlocking { dataSource.renameFile(oldUri, "renamed.org") }
                fail("expected IOException")
            } catch (expected: java.io.IOException) {
            }
        }
    }
}
