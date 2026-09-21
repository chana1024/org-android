package com.orgutil.domain.sync

sealed interface GitSyncRequestResult {
    data object Enqueued : GitSyncRequestResult
    data object NotConfigured : GitSyncRequestResult
    data class Failed(val message: String) : GitSyncRequestResult
}
