package com.orgutil.data.repository

import android.net.Uri
import com.orgutil.data.database.dao.FileDao
import com.orgutil.data.database.entity.FileContentEntity
import com.orgutil.data.database.entity.FileContentFtsEntity
import com.orgutil.data.database.entity.FileMetadataEntity
import com.orgutil.data.datasource.FileDataSource
import com.orgutil.data.mapper.OrgParserWrapper
import com.orgutil.domain.model.OrgDocument
import com.orgutil.domain.model.OrgFileInfo
import com.orgutil.domain.model.OrgNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

class OrgFileRepositoryImplTest {

    @Mock lateinit var fileDataSource: FileDataSource
    @Mock lateinit var orgParserWrapper: OrgParserWrapper
    @Mock lateinit var orgFileQueryService: OrgFileQueryService
    @Mock lateinit var mockUri: Uri
    @Mock lateinit var captureUri: Uri

    private lateinit var fileDao: RecordingFileDao
    private lateinit var repository: OrgFileRepositoryImpl

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        fileDao = RecordingFileDao()
        repository = OrgFileRepositoryImpl(fileDao, orgParserWrapper, fileDataSource, orgFileQueryService)
        `when`(mockUri.toString()).thenReturn("content://org/test.org")
        `when`(captureUri.toString()).thenReturn("content://org/gtd/inbox.org")
    }

    @Test
    fun `getOrgFiles returns files from query service`() = runTest {
        val mockFiles = listOf(
            OrgFileInfo(
                uri = mockUri,
                name = "test.org",
                lastModified = 123456789L,
                size = 1024L
            )
        )
        `when`(orgFileQueryService.observeOrgFiles(null, null, false)).thenReturn(flowOf(mockFiles))

        val result = repository.getOrgFiles().first()

        assertEquals(mockFiles, result)
        verify(orgFileQueryService).observeOrgFiles(null, null, false)
    }

    @Test
    fun `readOrgFile returns success when file is read successfully`() = runTest {
        val fileContent = "* Header\n** Subheader\nContent"
        val mockNodes = listOf(
            OrgNode(level = 1, title = "Header", content = "Content")
        )
        `when`(mockUri.path).thenReturn("/tmp/test.org")
        `when`(fileDataSource.readFile(mockUri)).thenReturn(fileContent)
        `when`(orgParserWrapper.parseContent(fileContent)).thenReturn("" to mockNodes)

        val result = repository.readOrgFile(mockUri)

        assertTrue(result.isSuccess)
        verify(fileDataSource).readFile(mockUri)
        verify(orgParserWrapper).parseContent(fileContent)
    }

    @Test
    fun `writeOrgFile returns success when file is written successfully`() = runTest {
        val document = OrgDocument(
            uri = mockUri,
            fileName = "test.org",
            content = "* Header\nContent",
            lastModified = 123456789L,
            nodes = emptyList()
        )
        val fileInfo = OrgFileInfo(
            uri = mockUri,
            name = document.fileName,
            lastModified = 123456789L,
            size = document.content.length.toLong()
        )
        `when`(fileDataSource.readFile(mockUri)).thenReturn(document.content)
        `when`(fileDataSource.getFileInfo(mockUri)).thenReturn(fileInfo)

        val result = repository.writeOrgFile(document)

        assertTrue(result.isSuccess)
        verify(fileDataSource).writeFile(mockUri, document.content)
        verify(fileDataSource, times(2)).readFile(mockUri)
        verify(fileDataSource).getFileInfo(mockUri)

        val metadata = requireNotNull(fileDao.lastInsertedMetadata)
        val indexedContent = requireNotNull(fileDao.lastInsertedFtsContent)
        assertEquals(mockUri.toString(), metadata.path)
        assertEquals(document.fileName, metadata.fileName)
        assertEquals(document.content.length.toLong(), metadata.size)
        assertEquals(mockUri.toString(), indexedContent.path)
        assertEquals(document.content, indexedContent.content)
    }

    @Test
    fun `writeOrgFile encodes CJK content for FTS while storing raw content`() = runTest {
        val rawContent = "* 中文测试混合\nsome latin"
        val document = OrgDocument(
            uri = mockUri,
            fileName = "test.org",
            content = rawContent,
            lastModified = 42L,
            nodes = emptyList()
        )
        `when`(fileDataSource.readFile(mockUri)).thenReturn(rawContent)
        `when`(fileDataSource.getFileInfo(mockUri)).thenReturn(null)

        val result = repository.writeOrgFile(document)

        assertTrue(result.isSuccess)
        val fts = requireNotNull(fileDao.lastInsertedFtsContent)
        val plain = requireNotNull(fileDao.lastInsertedPlainContent)
        // FTS row holds the bigram-encoded form so "测试" can match by bigram.
        assertTrue(fts.content.contains("测试"))
        assertTrue(fts.content.contains("文测"))
        assertNotEquals(rawContent, fts.content)
        // Plain content table keeps the original body (previews, point reads).
        assertEquals(rawContent, plain.content)
    }

    @Test
    fun `createOrgFile indexes created file and normalizes org suffix when metadata missing`() = runTest {
        val fileContent = "* Inbox"
        `when`(fileDataSource.createFile("inbox", fileContent)).thenReturn(mockUri)
        `when`(fileDataSource.readFile(mockUri)).thenReturn(fileContent)
        `when`(fileDataSource.getFileInfo(mockUri)).thenReturn(null)

        val result = repository.createOrgFile("inbox", fileContent)

        assertTrue(result.isSuccess)
        assertEquals(mockUri, result.getOrNull())
        verify(fileDataSource).createFile("inbox", fileContent)

        val metadata = requireNotNull(fileDao.lastInsertedMetadata)
        val indexedContent = requireNotNull(fileDao.lastInsertedFtsContent)
        assertEquals("inbox.org", metadata.fileName)
        assertEquals(fileContent.length.toLong(), metadata.size)
        assertEquals(fileContent, indexedContent.content)
    }

    @Test
    fun `deleteOrgFile removes source and all indexed entries`() = runTest {
        val result = repository.deleteOrgFile(mockUri)

        assertTrue(result.isSuccess)
        verify(fileDataSource).deleteFile(mockUri)
        assertEquals(listOf(mockUri.toString()), fileDao.deletedMetadataPaths)
        assertEquals(listOf(mockUri.toString()), fileDao.deletedContentPaths)
    }

    @Test
    fun `appendToCaptureFile updates indexed inbox content`() = runTest {
        val appendedContent = "* TODO Buy milk"
        `when`(fileDataSource.getCaptureFileUri()).thenReturn(captureUri)
        `when`(fileDataSource.readFile(captureUri)).thenReturn(appendedContent)
        `when`(fileDataSource.getFileInfo(captureUri)).thenReturn(null)

        val result = repository.appendToCaptureFile(appendedContent)

        assertTrue(result.isSuccess)
        verify(fileDataSource).appendToCaptureFile(appendedContent)
        verify(fileDataSource).getCaptureFileUri()

        val metadata = requireNotNull(fileDao.lastInsertedMetadata)
        val indexedContent = requireNotNull(fileDao.lastInsertedFtsContent)
        assertEquals(captureUri.toString(), metadata.path)
        assertEquals("inbox.org", metadata.fileName)
        assertEquals(appendedContent.length.toLong(), metadata.size)
        assertEquals(captureUri.toString(), indexedContent.path)
        assertEquals(appendedContent, indexedContent.content)
    }

    @Test
    fun `appendToCaptureFile fails when capture uri is unavailable after append`() = runTest {
        `when`(fileDataSource.getCaptureFileUri()).thenReturn(null)

        val result = repository.appendToCaptureFile("* TODO Missing URI")

        assertFalse(result.isSuccess)
        val exception = result.exceptionOrNull()
        assertTrue(exception is IllegalStateException)
        assertEquals("Capture file URI unavailable after append", exception?.message)
        verify(fileDataSource).appendToCaptureFile("* TODO Missing URI")
        assertEquals(null, fileDao.lastInsertedMetadata)
        assertEquals(null, fileDao.lastInsertedFtsContent)
        assertTrue(fileDao.deletedMetadataPaths.isEmpty())
        assertTrue(fileDao.deletedContentPaths.isEmpty())
    }

    /**
     * Records writes per column family. The @Transaction default methods of
     * [FileDao] run against this fake unchanged, so the delete-then-insert
     * ordering is exercised exactly as in production.
     */
    private class RecordingFileDao : FileDao {
        var lastInsertedMetadata: FileMetadataEntity? = null
        var lastInsertedFtsContent: FileContentFtsEntity? = null
        var lastInsertedPlainContent: FileContentEntity? = null
        var deletedMetadataPaths: List<String> = emptyList()
        var deletedContentPaths: List<String> = emptyList()

        override suspend fun insertFileMetadata(metadata: FileMetadataEntity) {
            lastInsertedMetadata = metadata
        }

        override suspend fun insertAllFileMetadata(metadata: List<FileMetadataEntity>) {
            lastInsertedMetadata = metadata.lastOrNull()
        }

        override fun getAllFileMetadata(): Flow<List<FileMetadataEntity>> = flowOf(emptyList())

        override suspend fun getFileMetadataByPaths(paths: List<String>): List<FileMetadataEntity> = emptyList()

        override suspend fun getFileMetadataByPath(path: String): FileMetadataEntity? = null

        override suspend fun deleteFileMetadataByPaths(paths: List<String>) {
            deletedMetadataPaths = paths
        }

        override suspend fun insertFileContentFts(content: FileContentFtsEntity) {
            lastInsertedFtsContent = content
        }

        override suspend fun insertAllFileContentFts(content: List<FileContentFtsEntity>) {
            lastInsertedFtsContent = content.lastOrNull()
        }

        override suspend fun insertFileContentRow(content: FileContentEntity) {
            lastInsertedPlainContent = content
        }

        override suspend fun insertAllFileContentRows(content: List<FileContentEntity>) {
            lastInsertedPlainContent = content.lastOrNull()
        }

        override suspend fun deleteFileContentByPath(path: String) {
            deletedContentPaths = listOf(path)
        }

        override suspend fun deleteFileContentByPaths(paths: List<String>) {
            deletedContentPaths = paths
        }

        override suspend fun deletePlainContentByPaths(paths: List<String>) = Unit

        override suspend fun getFileContentByPath(path: String): FileContentEntity? = null

        override suspend fun searchFilesWithContent(ftsQuery: String): List<com.orgutil.data.database.entity.FileSearchResult> =
            emptyList()

        override suspend fun getMetadataPathsWithoutFtsContent(): List<String> = emptyList()

        override suspend fun getAllFilePaths(): List<String> = emptyList()
    }
}
