package com.orgutil.domain.repository

import com.orgutil.domain.sync.GitRepoSnapshot
import com.orgutil.domain.sync.GitSyncOutcome
import java.io.File

interface GitSyncRepository {
    suspend fun runSync(): Result<GitSyncOutcome>
    suspend fun testConnection(): Result<Unit>
    suspend fun getRepoSnapshot(): GitRepoSnapshot?
    suspend fun resolveRepoRoot(): Result<File>

    fun getRemoteUrl(): String?
    fun saveRemoteUrl(url: String)

    fun getRepoRootOverride(): String?
    fun saveRepoRootOverride(path: String)

    fun isAutoSyncEnabled(): Boolean
    fun setAutoSyncEnabled(enabled: Boolean)

    fun getLastSyncTime(): Long

    /** Returns (username, token) or null if not configured. */
    fun getCredentials(): Pair<String, String>?
    fun saveCredentials(username: String, token: String)
}
