package com.orgutil.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.orgutil.domain.usecase.RunFileIndexingUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltWorker
class FileIndexerWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val runFileIndexingUseCase: RunFileIndexingUseCase
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        safeLogD("FileIndexerWorker", "Starting file indexing work...")
        when (val outcome = FileIndexWorkerResultMapper.map(runFileIndexingUseCase())) {
            is FileIndexWorkerOutcome.Success -> {
                safeLogD("FileIndexerWorker", outcome.message)
                Result.success()
            }
            is FileIndexWorkerOutcome.Failure -> {
                safeLogE(
                    "FileIndexerWorker",
                    "File indexing failed: ${outcome.message}",
                    outcome.cause
                )
                Result.failure()
            }
        }
    }
}

private fun safeLogD(tag: String, message: String) {
    runCatching { Log.d(tag, message) }
}

private fun safeLogE(tag: String, message: String, throwable: Throwable? = null) {
    runCatching {
        if (throwable == null) {
            Log.e(tag, message)
        } else {
            Log.e(tag, message, throwable)
        }
    }
}
