package com.orgutil.worker

import com.orgutil.domain.usecase.FileIndexExecutionResult

internal sealed interface FileIndexWorkerOutcome {
    val message: String

    data class Success(
        override val message: String
    ) : FileIndexWorkerOutcome

    data class Failure(
        override val message: String,
        val cause: Throwable? = null
    ) : FileIndexWorkerOutcome
}

internal object FileIndexWorkerResultMapper {
    fun map(result: FileIndexExecutionResult): FileIndexWorkerOutcome {
        return when (result) {
            is FileIndexExecutionResult.Success -> {
                val report = result.report
                val failureSummary = report.contentFailures.firstOrNull()?.let { failure ->
                    ", firstFailure=${failure.path}: ${failure.reason}"
                }.orEmpty()
                FileIndexWorkerOutcome.Success(
                    message = "File indexing completed successfully. scanned=${report.scannedFileCount}, inserted=${report.insertedCount}, updated=${report.updatedCount}, deleted=${report.deletedCount}, skipped=${report.skippedContentCount}$failureSummary"
                )
            }
            is FileIndexExecutionResult.Failure -> FileIndexWorkerOutcome.Failure(
                message = result.message,
                cause = result.cause
            )
        }
    }
}
