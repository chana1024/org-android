package com.orgutil.domain.sync

import kotlinx.coroutines.flow.Flow

interface GitSyncScheduler {
    fun requestSync(): GitSyncRequestResult

    /**
     * Schedules a save-triggered sync when the remote is configured and
     * auto-sync is enabled. Implementations may debounce repeated saves.
     */
    fun requestSyncIfConfigured(): GitSyncRequestResult

    /** Enqueues the app-start sync immediately, when sync is configured. */
    fun requestStartupSyncIfConfigured(): GitSyncRequestResult = requestSyncIfConfigured()

    /** Enqueues a sync after a delayed save request has passed its debounce. */
    fun requestSyncAfterDebounce(): GitSyncRequestResult = requestSyncIfConfigured()

    fun observeSync(): Flow<GitSyncStatus>
}
