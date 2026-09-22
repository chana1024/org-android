package com.orgutil.data.repository

import android.net.Uri
import com.orgutil.data.database.dao.FileDao
import com.orgutil.data.database.entity.FileContentEntity
import com.orgutil.data.database.entity.FileContentFtsEntity
import com.orgutil.data.database.entity.FileMetadataEntity
import com.orgutil.data.datasource.FileDataSource
import com.orgutil.domain.model.OrgFileInfo
import com.orgutil.domain.search.CjkTextEncoder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

class OrgFileIndexUpdaterTest {

    @Mock lateinit var fileDao: FileDao
    @Mock lateinit var fileDataSource: FileDataSource

    private lateinit var updater: OrgFileIndexUpdater

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        updater = OrgFileIndexUpdater(fileDao, fileDataSource)
    }

    @Test
    fun `updateIndex transactionally writes metadata fts and plain content for new files`() = runTest {
        val file = orgFile(
            path = "content://org/new.org",
            name = "new.org",
            lastModified = 100L,
            size = 10L
        )
        `when`(fileDataSource.getAllOrgFiles()).thenReturn(listOf(file))
        `when`(fileDao.getAllFilePaths()).thenReturn(emptyList())
        `when`(fileDao.getMetadataPathsWithoutFtsContent()).thenReturn(emptyList())
        `when`(fileDataSource.readFile(file.uri)).thenReturn("* hello")

        val report = updater.run()

        verify(fileDao).replaceIndexedFiles(
            listOf(FileMetadataEntity(file.uri.toString(), "new.org", 100L, 10L)),
            listOf(FileContentFtsEntity(file.uri.toString(), "* hello")),
            listOf(FileContentEntity(file.uri.toString(), "* hello"))
        )
        assertEquals(
            FileIndexUpdateReport(
                scannedFileCount = 1,
                insertedCount = 1,
                updatedCount = 0,
                deletedCount = 0,
                repairedCount = 0
            ),
            report
        )
    }

    @Test
    fun `updateIndex encodes CJK content for FTS and keeps raw content`() = runTest {
        val file = orgFile("content://org/cn.org", "cn.org", 100L, 10L)
        `when`(fileDataSource.getAllOrgFiles()).thenReturn(listOf(file))
        `when`(fileDao.getAllFilePaths()).thenReturn(emptyList())
        `when`(fileDao.getMetadataPathsWithoutFtsContent()).thenReturn(emptyList())
        `when`(fileDataSource.readFile(file.uri)).thenReturn("中文测试混合")

        updater.run()

        // Exact-value verification (no matchers/captors): the FTS column must
        // hold the encoded form while the plain table keeps the raw body.
        val expectedEncoded = " 中文 文测 测试 试混 混合 中 文 测 试 混 合 "
        verify(fileDao).replaceIndexedFiles(
            listOf(FileMetadataEntity(file.uri.toString(), "cn.org", 100L, 10L)),
            listOf(FileContentFtsEntity(file.uri.toString(), expectedEncoded)),
            listOf(FileContentEntity(file.uri.toString(), "中文测试混合"))
        )
        // Sanity: the encoder really produces that form (guards the hardcoded
        // expectation above against encoder drift).
        assertEquals(expectedEncoded, CjkTextEncoder.encodeForIndex("中文测试混合"))
    }

    @Test
    fun `updateIndex updates changed files and removes deleted files`() = runTest {
        val updatedPath = "content://org/updated.org"
        val deletedPath = "content://org/deleted.org"
        val updatedFile = orgFile(
            path = updatedPath,
            name = "updated.org",
            lastModified = 200L,
            size = 20L
        )
        val oldMetadata = FileMetadataEntity(
            path = updatedPath,
            fileName = "updated.org",
            lastModified = 150L,
            size = 15L
        )
        `when`(fileDataSource.getAllOrgFiles()).thenReturn(listOf(updatedFile))
        `when`(fileDao.getAllFilePaths()).thenReturn(listOf(updatedPath, deletedPath))
        `when`(fileDao.getFileMetadataByPaths(listOf(updatedPath, deletedPath)))
            .thenReturn(listOf(oldMetadata))
        `when`(fileDao.getMetadataPathsWithoutFtsContent()).thenReturn(emptyList())
        `when`(fileDataSource.readFile(updatedFile.uri)).thenReturn("* updated")

        val report = updater.run()

        verify(fileDao).replaceIndexedFiles(
            listOf(FileMetadataEntity(updatedPath, "updated.org", 200L, 20L)),
            listOf(FileContentFtsEntity(updatedPath, "* updated")),
            listOf(FileContentEntity(updatedPath, "* updated"))
        )
        verify(fileDao).deleteIndexedFiles(listOf(deletedPath))

        assertEquals(
            FileIndexUpdateReport(
                scannedFileCount = 1,
                insertedCount = 0,
                updatedCount = 1,
                deletedCount = 1,
                repairedCount = 0
            ),
            report
        )
    }

    @Test
    fun `updateIndex writes no metadata when content read fails so next pass retries`() = runTest {
        val file = orgFile("content://org/broken.org", "broken.org", 300L, 30L)
        `when`(fileDataSource.getAllOrgFiles()).thenReturn(listOf(file))
        `when`(fileDao.getAllFilePaths()).thenReturn(emptyList())
        `when`(fileDao.getMetadataPathsWithoutFtsContent()).thenReturn(emptyList())
        `when`(fileDataSource.readFile(file.uri)).thenThrow(IllegalStateException("read failed"))

        val report = updater.run()

        // The new file must not be half-indexed: no transactional write at all.
        verify(fileDao, never()).replaceIndexedFiles(
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.anyList()
        )
        assertEquals(1, report.skippedContentCount)
        assertEquals(
            listOf(
                FileIndexContentFailure(
                    path = file.uri.toString(),
                    reason = "read failed"
                )
            ),
            report.contentFailures
        )
    }

    @Test
    fun `updateIndex self-heals metadata rows missing FTS content`() = runTest {
        val file = orgFile("content://org/healed.org", "healed.org", 400L, 40L)
        // Precompute: calling the Uri mock inside a thenReturn argument would
        // interrupt the ongoing stubbing (UnfinishedStubbingException).
        val path = file.uri.toString()
        `when`(fileDataSource.getAllOrgFiles()).thenReturn(listOf(file))
        `when`(fileDao.getAllFilePaths()).thenReturn(listOf(path))
        `when`(fileDao.getFileMetadataByPaths(listOf(path))).thenReturn(
            listOf(FileMetadataEntity(path, "healed.org", 400L, 40L))
        )
        // E.g. after the 3->4 migration wiped the FTS table.
        `when`(fileDao.getMetadataPathsWithoutFtsContent()).thenReturn(listOf(path))
        `when`(fileDataSource.readFile(file.uri)).thenReturn("* healed")

        val report = updater.run()

        verify(fileDao).replaceIndexedFiles(
            listOf(FileMetadataEntity(path, "healed.org", 400L, 40L)),
            listOf(FileContentFtsEntity(path, "* healed")),
            listOf(FileContentEntity(path, "* healed"))
        )
        assertEquals(1, report.repairedCount)
    }

    private fun orgFile(
        path: String,
        name: String,
        lastModified: Long,
        size: Long
    ): OrgFileInfo {
        val uri = org.mockito.Mockito.mock(Uri::class.java)
        `when`(uri.toString()).thenReturn(path)
        return OrgFileInfo(
            uri = uri,
            name = name,
            lastModified = lastModified,
            size = size
        )
    }
}
