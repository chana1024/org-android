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
        return workManagerGateway.observeUniqueWork(UNIQUE_WORK_NAME)
            .map { workInfos -> workInfos.toFileIndexStatus() }
    }

    private fun List<WorkInfo>.toFileIndexStatus(): FileIndexStatus {
        if (isEmpty()) return FileIndexStatus.Idle
        return when {
            any { it.state == WorkInfo.State.RUNNING } -> FileIndexStatus.Running
            any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } -> FileIndexStatus.Enqueued
            any { it.state == WorkInfo.State.FAILED } -> FileIndexStatus.Failed("Indexing failed")
            any { it.state == WorkInfo.State.CANCELLED } -> FileIndexStatus.Failed("Indexing cancelled")
            any { it.state == WorkInfo.State.SUCCEEDED } -> FileIndexStatus.Succeeded
            else -> FileIndexStatus.Idle
        }
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

private class AndroidxWorkManagerGateway(
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
