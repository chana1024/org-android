package com.orgutil.data.repository

import android.net.Uri
import com.orgutil.data.database.dao.FileDao
import com.orgutil.data.database.entity.FileContentFtsEntity
import com.orgutil.data.database.entity.FileMetadataEntity
import com.orgutil.data.datasource.FileDataSource
import com.orgutil.domain.model.OrgFileInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`
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
    fun `updateIndex inserts metadata and content for new files`() = runTest {
        val file = orgFile(
            path = "content://org/new.org",
            name = "new.org",
            lastModified = 100L,
            size = 10L
        )
        `when`(fileDataSource.getAllOrgFiles()).thenReturn(listOf(file))
        `when`(fileDao.getAllFilePaths()).thenReturn(emptyList())
        `when`(fileDao.getFileMetadataByPaths(emptyList())).thenReturn(emptyList())
        `when`(fileDataSource.readFile(file.uri)).thenReturn("* hello")

        val report = updater.run()

        verify(fileDao).insertAllFileMetadata(
            listOf(FileMetadataEntity(file.uri.toString(), "new.org", 100L, 10L))
        )
        verify(fileDao).insertAllFileContent(
            listOf(FileContentFtsEntity(file.uri.toString(), "* hello"))
        )
        assertEquals(
            FileIndexUpdateReport(
                scannedFileCount = 1,
                insertedCount = 1,
                updatedCount = 0,
                deletedCount = 0
            ),
            report
        )
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
        `when`(fileDataSource.readFile(updatedFile.uri)).thenReturn("* updated")

        val report = updater.run()

        verify(fileDao).insertAllFileMetadata(
            listOf(FileMetadataEntity(updatedPath, "updated.org", 200L, 20L))
        )
        verify(fileDao).insertAllFileContent(
            listOf(FileContentFtsEntity(updatedPath, "* updated"))
        )
        verify(fileDao).deleteFileMetadataByPaths(listOf(deletedPath))
        verify(fileDao).deleteFileContentByPaths(listOf(deletedPath))

        assertEquals(
            FileIndexUpdateReport(
                scannedFileCount = 1,
                insertedCount = 0,
                updatedCount = 1,
                deletedCount = 1
            ),
            report
        )
    }

    @Test
    fun `updateIndex records content failure details when file read fails`() = runTest {
        val file = orgFile(
            path = "content://org/broken.org",
            name = "broken.org",
            lastModified = 300L,
            size = 30L
        )
        `when`(fileDataSource.getAllOrgFiles()).thenReturn(listOf(file))
        `when`(fileDao.getAllFilePaths()).thenReturn(emptyList())
        `when`(fileDao.getFileMetadataByPaths(emptyList())).thenReturn(emptyList())
        `when`(fileDataSource.readFile(file.uri)).thenThrow(IllegalStateException("read failed"))

        val report = updater.run()

        verify(fileDao).insertAllFileMetadata(
            listOf(FileMetadataEntity(file.uri.toString(), "broken.org", 300L, 30L))
        )
        verify(fileDao).insertAllFileContent(emptyList())
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
