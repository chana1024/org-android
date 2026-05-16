package com.orgutil.domain.indexing

interface FileIndexScheduler {
    fun requestIndexing(): FileIndexRequestResult
    fun ensurePeriodicIndexing(): FileIndexRequestResult
}
