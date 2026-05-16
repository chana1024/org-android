package com.orgutil.domain.usecase

import com.orgutil.data.repository.FileIndexUpdateReport
import com.orgutil.domain.indexing.FileIndexRunner
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RunFileIndexingUseCase @Inject constructor(
    private val fileIndexRunner: FileIndexRunner
) {
    suspend operator fun invoke(): FileIndexExecutionResult {
        return runCatching { fileIndexRunner.run() }
            .fold(
                onSuccess = { FileIndexExecutionResult.Success(it) },
                onFailure = {
                    FileIndexExecutionResult.Failure(
                        message = it.message ?: "Unknown indexing failure",
                        cause = it
                    )
                }
            )
    }
}

sealed interface FileIndexExecutionResult {
    data class Success(val report: FileIndexUpdateReport) : FileIndexExecutionResult
    data class Failure(
        val message: String,
        val cause: Throwable? = null
    ) : FileIndexExecutionResult
}
