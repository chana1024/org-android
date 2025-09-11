package com.orgutil.data.repository

import android.net.Uri
import com.orgutil.data.datasource.FileDataSource
import com.orgutil.data.mapper.OrgParserWrapper
import com.orgutil.domain.model.OrgFileInfo
import com.orgutil.domain.model.OrgNode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations

class OrgFileRepositoryImplTest {

    @Mock
    private lateinit var fileDataSource: FileDataSource

    @Mock
    private lateinit var orgParserWrapper: OrgParserWrapper

    @Mock
    private lateinit var mockUri: Uri

    private lateinit var repository: OrgFileRepositoryImpl

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        repository = OrgFileRepositoryImpl(fileDataSource, orgParserWrapper)
    }

    @Test
    fun `getOrgFiles returns files from data source`() = runTest {
        // Given
        val mockFiles = listOf(
            OrgFileInfo(
                uri = mockUri,
                name = "test.org",
                lastModified = 123456789L,
                size = 1024L
            )
        )
        `when`(fileDataSource.getOrgFiles()).thenReturn(mockFiles)

        // When
        val result = repository.getOrgFiles().first()

        // Then
        assert(result == mockFiles)
        verify(fileDataSource).getOrgFiles()
    }

    @Test
    fun `readOrgFile returns success when file is read successfully`() = runTest {
        // Given
        val fileContent = "* Header\n** Subheader\nContent"
        val mockNodes = listOf(
            OrgNode(
                level = 1,
                title = "Header",
                content = "Content"
            )
        )
        `when`(fileDataSource.readFile(mockUri)).thenReturn(fileContent)
        `when`(orgParserWrapper.parseContent(fileContent)).thenReturn(mockNodes)

        // When
        val result = repository.readOrgFile(mockUri)

        // Then
        assert(result.isSuccess)
        verify(fileDataSource).readFile(mockUri)
        verify(orgParserWrapper).parseContent(fileContent)
    }

    @Test
    fun `writeOrgFile returns success when file is written successfully`() = runTest {
        // Given
        val mockNodes = listOf(
            OrgNode(
                level = 1,
                title = "Header",
                content = "Content"
            )
        )
        val expectedContent = "* Header\nContent"
        `when`(orgParserWrapper.writeContent(mockNodes)).thenReturn(expectedContent)

        val document = com.orgutil.domain.model.OrgDocument(
            uri = mockUri,
            fileName = "test.org",
            content = "original content",
            lastModified = 123456789L,
            nodes = mockNodes
        )

        // When
        val result = repository.writeOrgFile(document)

        // Then
        assert(result.isSuccess)
        verify(orgParserWrapper).writeContent(mockNodes)
        verify(fileDataSource).writeFile(mockUri, expectedContent)
    }
}