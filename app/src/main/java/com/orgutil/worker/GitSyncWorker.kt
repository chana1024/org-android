package com.orgutil.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.orgutil.domain.indexing.FileIndexScheduler
import com.orgutil.domain.usecase.RunGitSyncUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltWorker
class GitSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val runGitSyncUseCase: RunGitSyncUseCase,
    private val fileIndexScheduler: FileIndexScheduler
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        safeLogD(TAG, "Starting git sync work...")
        val outcome = try {
            runGitSyncUseCase().getOrThrow()
        } catch (e: Exception) {
            safeLogE(TAG, "Git sync failed: ${e.message}", e)
            return@withContext Result.failure(
                workDataOf(
                    KEY_ERROR to (e.message ?: "Git sync failed"),
                    KEY_SYNC_TIME to System.currentTimeMillis()
                )
            )
        }

        safeLogD(TAG, "Git sync finished: ${outcome.summary}")
        // Re-index so merged/changed files are visible in search/agenda/favorites
        runCatching { fileIndexScheduler.requestIndexing() }

        Result.success(
            workDataOf(
                KEY_SUMMARY to outcome.summary,
                KEY_CONFLICTED to outcome.conflictedFiles.joinToString(","),
                KEY_SYNC_TIME to System.currentTimeMillis()
            )
        )
    }

    companion object {
        const val KEY_STEP = "git_sync_step"
        const val KEY_SUMMARY = "git_sync_summary"
        const val KEY_CONFLICTED = "git_sync_conflicted"
        const val KEY_ERROR = "git_sync_error"
        const val KEY_SYNC_TIME = "git_sync_time"
        private const val TAG = "GitSyncWorker"
    }
}

private fun safeLogD(tag: String, message: String) {
    runCatching { Log.d(tag, message) }
}

private fun safeLogE(tag: String, message: String, throwable: Throwable? = null) {
    runCatching {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
}
