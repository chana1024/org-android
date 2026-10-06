package com.orgutil.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import com.orgutil.data.gcal.GcalConfigStore
import com.orgutil.domain.gcal.GcalScheduler
import com.orgutil.domain.gcal.GcalSyncRequestResult
import com.orgutil.domain.gcal.GcalSyncStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager scheduling for the Google Calendar sync, following
 * [WorkManagerGitSyncScheduler] (one-shot chain) and
 * [WorkManagerFileIndexScheduler] (periodic chain + merged observation).
 *
 * The periodic chain only exists while auto-sync is enabled AND the app is
 * authorized; unique names keep repeated scheduling idempotent.
 */
@Singleton
class WorkManagerGcalSyncScheduler internal constructor(
    private val workManagerGateway: WorkManagerGateway,
    private val configStore: GcalConfigStore
) : GcalScheduler {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        configStore: GcalConfigStore
    ) : this(AndroidxWorkManagerGateway(context), configStore)

    override fun requestSync(): GcalSyncRequestResult {
        return runCatching {
            val request = OneTimeWorkRequestBuilder<GcalSyncWorker>()
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            workManagerGateway.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
            GcalSyncRequestResult.Enqueued
        }.getOrElse {
            GcalSyncRequestResult.Failed(it.message ?: "Unknown scheduling failure")
        }
    }

    override fun ensurePeriodicSync(): GcalSyncRequestResult {
        if (!configStore.isAuthorized() || !configStore.isAutoSyncEnabled()) {
            return GcalSyncRequestResult.NotConfigured
        }
        return runCatching {
            val periodic = PeriodicWorkRequestBuilder<GcalSyncWorker>(
                PERIODIC_INTERVAL_MINUTES,
                TimeUnit.MINUTES
            )
                .setConstraints(networkConstraints())
                .build()
            workManagerGateway.enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                // UPDATE: re-arms idempotently on every app start without
                // discarding pending periodic work.
                ExistingPeriodicWorkPolicy.UPDATE,
                periodic
            )
            GcalSyncRequestResult.Enqueued
        }.getOrElse {
            GcalSyncRequestResult.Failed(it.message ?: "Unknown scheduling failure")
        }
    }

    override fun cancelPeriodicSync() {
        runCatching { workManagerGateway.cancelUniqueWork(PERIODIC_WORK_NAME) }
    }

    override fun observeSync(): Flow<GcalSyncStatus> {
        val oneShot = workManagerGateway.observeUniqueWork(UNIQUE_WORK_NAME)
            .map { workInfos -> workInfos.toGcalSyncStatus() }
        // A periodic chain rests in ENQUEUED between runs - its idle state.
        val periodic = workManagerGateway.observeUniqueWork(PERIODIC_WORK_NAME)
            .map { workInfos -> workInfos.toGcalSyncStatus() }
            .map { status -> if (status == GcalSyncStatus.Enqueued) GcalSyncStatus.Idle else status }
        return combine(oneShot, periodic, ::mergeStatus)
    }

    private fun networkConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    /** Latest-record-wins, mirroring the git sync / indexer chains. */
    private fun List<WorkInfo>.toGcalSyncStatus(): GcalSyncStatus {
        val latest = lastOrNull() ?: return GcalSyncStatus.Idle
        return when (latest.state) {
            WorkInfo.State.RUNNING -> GcalSyncStatus.Running(
                latest.progress.getString(GcalSyncWorker.KEY_STEP) ?: "Syncing Google Calendar"
            )
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED -> GcalSyncStatus.Enqueued
            WorkInfo.State.FAILED -> GcalSyncStatus.Failed(
                latest.outputData.getString(GcalSyncWorker.KEY_ERROR) ?: "Google Calendar sync failed",
                latest.outputData.getLong(GcalSyncWorker.KEY_SYNC_TIME, 0L)
            )
            WorkInfo.State.CANCELLED -> GcalSyncStatus.Failed("Google Calendar sync cancelled", 0L)
            WorkInfo.State.SUCCEEDED -> {
                val output = latest.outputData
                val at = output.getLong(GcalSyncWorker.KEY_SYNC_TIME, 0L)
                if (output.getBoolean(GcalSyncWorker.KEY_AUTH_REQUIRED, false)) {
                    GcalSyncStatus.NeedsAuthorization(
                        output.getString(GcalSyncWorker.KEY_ERROR) ?: "authorization required",
                        at
                    )
                } else {
                    GcalSyncStatus.Succeeded(
                        output.getString(GcalSyncWorker.KEY_SUMMARY) ?: "Synced",
                        at
                    )
                }
            }
        }
    }

    /** Active work wins; past that, the one-shot chain reflects explicit user actions. */
    private fun mergeStatus(oneShot: GcalSyncStatus, periodic: GcalSyncStatus): GcalSyncStatus {
        if (oneShot is GcalSyncStatus.Running || periodic is GcalSyncStatus.Running) {
            return oneShot as? GcalSyncStatus.Running ?: periodic
        }
        if (oneShot == GcalSyncStatus.Enqueued) return GcalSyncStatus.Enqueued
        if (oneShot != GcalSyncStatus.Idle) return oneShot
        if (periodic != GcalSyncStatus.Idle && periodic != GcalSyncStatus.Enqueued) return periodic
        return GcalSyncStatus.Idle
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "gcal_sync"
        private const val PERIODIC_WORK_NAME = "gcal_sync_periodic"
        private const val PERIODIC_INTERVAL_MINUTES = 30L
    }
}
