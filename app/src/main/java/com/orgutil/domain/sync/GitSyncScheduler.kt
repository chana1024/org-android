package com.orgutil.domain.sync

import kotlinx.coroutines.flow.Flow

interface GitSyncScheduler {
    fun requestSync(): GitSyncRequestResult

    /**
     * Enqueues a sync only when the remote is configured and auto-sync is
     * enabled; used for the fire-and-forget sync on app launch.
     */
    fun requestSyncIfConfigured(): GitSyncRequestResult
    fun observeSync(): Flow<GitSyncStatus>
}
