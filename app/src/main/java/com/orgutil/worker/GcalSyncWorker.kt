package com.orgutil.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.orgutil.data.gcal.GcalSyncEngine
import com.orgutil.data.gcal.GcalSyncResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs one Google Calendar sync (scan -> plan -> apply). Authorization-needed
 * outcomes are reported as success-with-auth_required so periodic work does
 * not retry-storm: the Sync screen surfaces the status and relaunches consent
 * in the foreground (a worker can never launch consent UI).
 */
@HiltWorker
class GcalSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: GcalSyncEngine
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        setProgress(workDataOf(KEY_STEP to "Scanning GTD sources"))
        return when (val result = engine.sync()) {
            is GcalSyncResult.Success -> Result.success(
                workDataOf(
                    KEY_SUMMARY to result.outcome.detail,
                    KEY_SYNC_TIME to System.currentTimeMillis()
                )
            )
            is GcalSyncResult.NeedsAuthorization -> Result.success(
                workDataOf(
                    KEY_AUTH_REQUIRED to true,
                    KEY_ERROR to result.message,
                    KEY_SYNC_TIME to System.currentTimeMillis()
                )
            )
            is GcalSyncResult.Failure -> Result.failure(
                workDataOf(
                    KEY_ERROR to result.message,
                    KEY_SYNC_TIME to System.currentTimeMillis()
                )
            )
        }
    }

    companion object {
        const val KEY_STEP = "step"
        const val KEY_SUMMARY = "summary"
        const val KEY_ERROR = "error"
        const val KEY_AUTH_REQUIRED = "auth_required"
        const val KEY_SYNC_TIME = "sync_time"
    }
}
