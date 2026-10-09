package com.orgutil.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.orgutil.domain.sync.GitSyncRequestResult
import com.orgutil.domain.sync.GitSyncScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class GitSyncDebounceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val gitSyncScheduler: GitSyncScheduler
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return when (gitSyncScheduler.requestSyncAfterDebounce()) {
            GitSyncRequestResult.Enqueued,
            GitSyncRequestResult.NotConfigured -> Result.success()
            is GitSyncRequestResult.Failed -> Result.retry()
        }
    }
}
