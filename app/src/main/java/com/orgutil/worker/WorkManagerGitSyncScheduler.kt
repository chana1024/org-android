package com.orgutil.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import com.orgutil.data.datasource.GitSyncConfigStore
import com.orgutil.domain.sync.GitSyncRequestResult
import com.orgutil.domain.sync.GitSyncScheduler
import com.orgutil.domain.sync.GitSyncStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkManagerGitSyncScheduler internal constructor(
    private val workManagerGateway: WorkManagerGateway,
    private val configStore: GitSyncConfigStore
) : GitSyncScheduler {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        configStore: GitSyncConfigStore
    ) : this(AndroidxWorkManagerGateway(context), configStore)

    override fun requestSync(): GitSyncRequestResult {
        return runCatching {
            val syncRequest = OneTimeWorkRequestBuilder<GitSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            workManagerGateway.enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                syncRequest
            )
            GitSyncRequestResult.Enqueued
        }.getOrElse {
            GitSyncRequestResult.Failed(it.message ?: "Unknown scheduling failure")
        }
    }

    override fun requestSyncIfConfigured(): GitSyncRequestResult {
        if (!configStore.isConfigured()) return GitSyncRequestResult.NotConfigured
        if (!configStore.isAutoSyncEnabled()) return GitSyncRequestResult.NotConfigured
        return requestSync()
    }

    override fun observeSync(): Flow<GitSyncStatus> {
        return workManagerGateway.observeUniqueWork(UNIQUE_WORK_NAME)
            .map { workInfos -> workInfos.toGitSyncStatus() }
    }

    private fun List<WorkInfo>.toGitSyncStatus(): GitSyncStatus {
        if (isEmpty()) return GitSyncStatus.Idle
        return when {
            any { it.state == WorkInfo.State.RUNNING } -> {
                val running = first { it.state == WorkInfo.State.RUNNING }
                GitSyncStatus.Running(running.progress.getString(GitSyncWorker.KEY_STEP) ?: "Syncing")
            }
            any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } ->
                GitSyncStatus.Enqueued
            any { it.state == WorkInfo.State.FAILED } -> {
                val failed = first { it.state == WorkInfo.State.FAILED }
                GitSyncStatus.Failed(
                    failed.outputData.getString(GitSyncWorker.KEY_ERROR) ?: "Git sync failed",
                    failed.outputData.getLong(GitSyncWorker.KEY_SYNC_TIME, 0L)
                )
            }
            any { it.state == WorkInfo.State.CANCELLED } ->
                GitSyncStatus.Failed("Git sync cancelled", 0L)
            any { it.state == WorkInfo.State.SUCCEEDED } -> {
                val succeeded = first { it.state == WorkInfo.State.SUCCEEDED }
                val output = succeeded.outputData
                val conflicted = output.getString(GitSyncWorker.KEY_CONFLICTED)
                    ?.split(',')
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
                val at = output.getLong(GitSyncWorker.KEY_SYNC_TIME, 0L)
                if (conflicted.isNotEmpty()) {
                    GitSyncStatus.Conflict(conflicted, at)
                } else {
                    GitSyncStatus.Succeeded(
                        output.getString(GitSyncWorker.KEY_SUMMARY) ?: "Synced",
                        at
                    )
                }
            }
            else -> GitSyncStatus.Idle
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "git_sync"
    }
}
