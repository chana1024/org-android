package com.orgutil.domain.usecase

import com.orgutil.data.repository.FileIndexContentFailure
import com.orgutil.data.repository.FileIndexUpdateReport
import com.orgutil.domain.indexing.FileIndexRunner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

class RunFileIndexingUseCaseTest {

    @Mock lateinit var fileIndexRunner: FileIndexRunner

    private lateinit var useCase: RunFileIndexingUseCase

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        useCase = RunFileIndexingUseCase(fileIndexRunner)
    }

    @Test
    fun `invoke returns Success with report when runner succeeds`() = runTest {
        val expectedReport = FileIndexUpdateReport(
            scannedFileCount = 5,
            insertedCount = 2,
            updatedCount = 2,
            deletedCount = 1,
            contentFailures = listOf(
                FileIndexContentFailure(
                    path = "content://org/broken.org",
                    reason = "read failed"
                )
            )
        )
        `when`(fileIndexRunner.run()).thenReturn(expectedReport)

        val result = useCase()

        verify(fileIndexRunner).run()
        assertTrue(result is FileIndexExecutionResult.Success)
        val success = result as FileIndexExecutionResult.Success
        assertEquals(expectedReport, success.report)
        assertEquals(1, success.report.skippedContentCount)
    }

    @Test
    fun `invoke returns Failure with message when runner throws`() = runTest {
        val expectedMessage = "Indexing failed"
        val cause = RuntimeException(expectedMessage)
        `when`(fileIndexRunner.run()).thenThrow(cause)

        val result = useCase()

        verify(fileIndexRunner).run()
        assertTrue(result is FileIndexExecutionResult.Failure)
        val failure = result as FileIndexExecutionResult.Failure
        assertEquals(expectedMessage, failure.message)
        assertEquals(cause, failure.cause)
    }
}
