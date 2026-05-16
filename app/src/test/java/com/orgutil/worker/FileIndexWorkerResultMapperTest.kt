package com.orgutil.worker

import com.orgutil.data.repository.FileIndexContentFailure
import com.orgutil.data.repository.FileIndexUpdateReport
import com.orgutil.domain.usecase.FileIndexExecutionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileIndexWorkerResultMapperTest {

    @Test
    fun `map returns success outcome with summary message`() {
        val result = FileIndexExecutionResult.Success(
            FileIndexUpdateReport(
                scannedFileCount = 4,
                insertedCount = 1,
                updatedCount = 2,
                deletedCount = 1
            )
        )

        val outcome = FileIndexWorkerResultMapper.map(result)

        assertTrue(outcome is FileIndexWorkerOutcome.Success)
        val success = outcome as FileIndexWorkerOutcome.Success
        assertEquals(
            "File indexing completed successfully. scanned=4, inserted=1, updated=2, deleted=1, skipped=0",
            success.message
        )
    }

    @Test
    fun `map appends first content failure detail when available`() {
        val result = FileIndexExecutionResult.Success(
            FileIndexUpdateReport(
                scannedFileCount = 4,
                insertedCount = 1,
                updatedCount = 2,
                deletedCount = 1,
                contentFailures = listOf(
                    FileIndexContentFailure(
                        path = "content://org/broken.org",
                        reason = "read failed"
                    )
                )
            )
        )

        val outcome = FileIndexWorkerResultMapper.map(result)

        assertTrue(outcome is FileIndexWorkerOutcome.Success)
        val success = outcome as FileIndexWorkerOutcome.Success
        assertEquals(
            "File indexing completed successfully. scanned=4, inserted=1, updated=2, deleted=1, skipped=1, firstFailure=content://org/broken.org: read failed",
            success.message
        )
    }

    @Test
    fun `map returns failure outcome with cause`() {
        val cause = IllegalStateException("boom")
        val result = FileIndexExecutionResult.Failure(
            message = "File indexing failed: boom",
            cause = cause
        )

        val outcome = FileIndexWorkerResultMapper.map(result)

        assertTrue(outcome is FileIndexWorkerOutcome.Failure)
        val failure = outcome as FileIndexWorkerOutcome.Failure
        assertEquals("File indexing failed: boom", failure.message)
        assertEquals(cause, failure.cause)
    }

    @Test
    fun `map returns failure outcome without cause when missing`() {
        val result = FileIndexExecutionResult.Failure(message = "no cause")

        val outcome = FileIndexWorkerResultMapper.map(result)

        assertTrue(outcome is FileIndexWorkerOutcome.Failure)
        val failure = outcome as FileIndexWorkerOutcome.Failure
        assertEquals("no cause", failure.message)
        assertNull(failure.cause)
    }
}
