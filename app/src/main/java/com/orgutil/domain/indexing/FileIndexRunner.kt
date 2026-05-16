package com.orgutil.domain.indexing

import com.orgutil.data.repository.FileIndexUpdateReport

/**
 * Pure execution boundary for file indexing.
 * Implementations may depend on Android frameworks; consumers (use cases, tests) only see this interface.
 */
interface FileIndexRunner {
    suspend fun run(): FileIndexUpdateReport
}
