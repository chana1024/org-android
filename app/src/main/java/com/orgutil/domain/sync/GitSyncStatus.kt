package com.orgutil.domain.sync

sealed interface GitSyncStatus {
    data object Idle : GitSyncStatus
    data object Enqueued : GitSyncStatus
    data class Running(val step: String) : GitSyncStatus
    data class Succeeded(val detail: String, val at: Long) : GitSyncStatus
    data class Conflict(val files: List<String>, val at: Long) : GitSyncStatus
    data class Failed(val message: String, val at: Long) : GitSyncStatus
}
