package com.orgutil.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.orgutil.domain.indexing.FileIndexRequestResult
import com.orgutil.domain.indexing.FileIndexScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
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
}
