package com.orgutil.domain.indexing

sealed interface FileIndexRequestResult {
    data object Enqueued : FileIndexRequestResult
    data class Failed(val message: String) : FileIndexRequestResult
}
