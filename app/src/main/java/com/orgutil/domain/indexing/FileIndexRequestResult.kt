package com.orgutil.domain.indexing

sealed interface FileIndexRequestResult {
    data object Enqueued : FileIndexRequestResult
    data class Failed(val message: String) : FileIndexRequestResult
}

sealed interface FileIndexStatus {
    data object Idle : FileIndexStatus
    data object Enqueued : FileIndexStatus
    data object Running : FileIndexStatus
    data object Succeeded : FileIndexStatus
    data class Failed(val message: String) : FileIndexStatus
}
