package com.orgutil.domain.indexing

import kotlinx.coroutines.flow.Flow

interface FileIndexScheduler {
    fun requestIndexing(): FileIndexRequestResult
    fun ensurePeriodicIndexing(): FileIndexRequestResult
    fun observeIndexing(): Flow<FileIndexStatus>
}
