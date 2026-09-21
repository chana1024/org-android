package com.orgutil.data.repository

import com.orgutil.data.datasource.GitCredentialStore
import com.orgutil.data.datasource.GitSyncConfigStore
import com.orgutil.data.git.JGitSyncDataSource
import com.orgutil.data.git.RepoPathResolver
import com.orgutil.domain.repository.GitSyncRepository
import com.orgutil.domain.sync.GitRepoSnapshot
import com.orgutil.domain.sync.GitSyncOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitSyncRepositoryImpl @Inject constructor(
    private val gitSyncDataSource: JGitSyncDataSource,
    private val configStore: GitSyncConfigStore,
    private val credentialStore: GitCredentialStore,
    private val repoPathResolver: RepoPathResolver
) : GitSyncRepository {

    override suspend fun runSync(): Result<GitSyncOutcome> = gitSyncDataSource.sync()

    override suspend fun testConnection(): Result<Unit> = gitSyncDataSource.testConnection()

    override suspend fun getRepoSnapshot(): GitRepoSnapshot? = gitSyncDataSource.getShortStatus()

    override suspend fun resolveRepoRoot(): Result<File> {
        val result = repoPathResolver.resolveRepoRoot()
        return withContext(Dispatchers.IO) { result }
    }

    override fun getRemoteUrl(): String? = configStore.getRemoteUrl()

    override fun saveRemoteUrl(url: String) {
        configStore.setRemoteUrl(url)
        // Remote change invalidates cached path decisions
        repoPathResolver.clearCache()
    }

    override fun getRepoRootOverride(): String? = configStore.getRepoRootOverride()

    override fun saveRepoRootOverride(path: String) {
        configStore.setRepoRootOverride(path)
        repoPathResolver.clearCache()
    }

    override fun isAutoSyncEnabled(): Boolean = configStore.isAutoSyncEnabled()

    override fun setAutoSyncEnabled(enabled: Boolean) {
        configStore.setAutoSyncEnabled(enabled)
    }

    override fun getLastSyncTime(): Long = configStore.getLastSyncTime()

    override fun getCredentials(): Pair<String, String>? = credentialStore.getCredentials()

    override fun saveCredentials(username: String, token: String) {
        credentialStore.store(username, token)
    }
}
