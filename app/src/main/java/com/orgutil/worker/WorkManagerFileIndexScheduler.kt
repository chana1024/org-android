package com.orgutil.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.orgutil.domain.indexing.FileIndexRequestResult
import com.orgutil.domain.indexing.FileIndexScheduler
import com.orgutil.domain.indexing.FileIndexStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkManagerFileIndexScheduler internal constructor(
    private val workManagerGateway: WorkManagerGateway
) : FileIndexScheduler {
    @Inject
    constructor(@ApplicationContext context: Context) : this(AndroidxWorkManagerGateway(context))

    override fun requestIndexing(): FileIndexRequestResult {
        return runCatching {
            val indexingRequest = OneTimeWorkRequestBuilder<FileIndexerWorker>().build()
            workManagerGateway.enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                indexingRequest
            )
            FileIndexRequestResult.Enqueued
        }.getOrElse {
            FileIndexRequestResult.Failed(it.message ?: "Unknown scheduling failure")
        }
    }

    override fun ensurePeriodicIndexing(): FileIndexRequestResult {
        return runCatching {
            val periodicWorkRequest = PeriodicWorkRequestBuilder<FileIndexerWorker>(
                PERIODIC_REPEAT_INTERVAL_MINUTES,
                TimeUnit.MINUTES
            ).build()
            workManagerGateway.enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicWorkRequest
            )
            FileIndexRequestResult.Enqueued
        }.getOrElse {
            FileIndexRequestResult.Failed(it.message ?: "Unknown scheduling failure")
        }
    }

    override fun observeIndexing(): Flow<FileIndexStatus> {
        // Both chains index the same data: the one-time chain serves explicit
        // refreshes, the periodic chain re-indexes every 15 minutes. A
        // success in either means the index is usable, so the monitor must
        // watch both - otherwise an old one-time failure keeps showing
        // "Indexing failed" while the periodic chain keeps the index fresh.
        val oneTimeStatus = workManagerGateway.observeUniqueWork(UNIQUE_WORK_NAME)
            .map { workInfos -> workInfos.toFileIndexStatus() }
        // A periodic chain rests in ENQUEUED between its 15-minute runs (it
        // never ends up in a terminal state), so ENQUEUED is its idle state
        // here - only RUNNING and terminal outcomes are meaningful.
        val periodicStatus = workManagerGateway.observeUniqueWork(UNIQUE_PERIODIC_WORK_NAME)
            .map { workInfos -> workInfos.toFileIndexStatus() }
            .map { status ->
                if (status == FileIndexStatus.Enqueued) FileIndexStatus.Idle else status
            }
        return combine(oneTimeStatus, periodicStatus, ::mergeStatus)
    }

    /**
     * Maps one unique work chain to the status of its LATEST record.
     *
     * getWorkInfosForUniqueWorkFlow returns the chain's full history (terminal
     * records survive until pruned). Aggregating with `any { FAILED }` lets a
     * stale failure permanently mask newer successes - the "Indexing failed"
     * chip that never clears. WorkInfo records arrive in insertion order, so
     * the last element is the most recent run and is the only one that
     * reflects the current state of the index.
     */
    private fun List<WorkInfo>.toFileIndexStatus(): FileIndexStatus {
        return when (lastOrNull()?.state) {
            WorkInfo.State.RUNNING -> FileIndexStatus.Running
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED -> FileIndexStatus.Enqueued
            WorkInfo.State.FAILED -> FileIndexStatus.Failed("Indexing failed")
            WorkInfo.State.CANCELLED -> FileIndexStatus.Failed("Indexing cancelled")
            WorkInfo.State.SUCCEEDED -> FileIndexStatus.Succeeded
            null -> FileIndexStatus.Idle
        }
    }

    /** Active work wins; past that, a success anywhere means the index is usable. */
    private fun mergeStatus(oneTime: FileIndexStatus, periodic: FileIndexStatus): FileIndexStatus {
        if (oneTime == FileIndexStatus.Running || periodic == FileIndexStatus.Running) {
            return FileIndexStatus.Running
        }
        if (oneTime == FileIndexStatus.Enqueued) {
            return FileIndexStatus.Enqueued
        }
        if (oneTime == FileIndexStatus.Succeeded || periodic == FileIndexStatus.Succeeded) {
            return FileIndexStatus.Succeeded
        }
        if (oneTime is FileIndexStatus.Failed) return oneTime
        if (periodic is FileIndexStatus.Failed) return periodic
        return FileIndexStatus.Idle
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "file_indexing"
        private const val UNIQUE_PERIODIC_WORK_NAME = "file-indexer"
        private const val PERIODIC_REPEAT_INTERVAL_MINUTES = 15L
    }
}

internal interface WorkManagerGateway {
    fun enqueueUniqueWork(
        name: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest
    )

    fun enqueueUniquePeriodicWork(
        name: String,
        policy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest
    )

    fun observeUniqueWork(name: String): Flow<List<WorkInfo>>
}

internal class AndroidxWorkManagerGateway(
    context: Context
) : WorkManagerGateway {
    private val workManager = WorkManager.getInstance(context)

    override fun enqueueUniqueWork(
        name: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest
    ) {
        workManager.enqueueUniqueWork(name, policy, request)
    }

    override fun enqueueUniquePeriodicWork(
        name: String,
        policy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest
    ) {
        workManager.enqueueUniquePeriodicWork(name, policy, request)
    }

    override fun observeUniqueWork(name: String): Flow<List<WorkInfo>> {
        return workManager.getWorkInfosForUniqueWorkFlow(name)
    }
}
