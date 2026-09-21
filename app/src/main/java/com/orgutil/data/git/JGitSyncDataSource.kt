package com.orgutil.data.git

import com.orgutil.data.datasource.GitCredentialStore
import com.orgutil.data.datasource.GitSyncConfigStore
import com.orgutil.domain.sync.GitRepoSnapshot
import com.orgutil.domain.sync.GitSyncOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.merge.MergeStrategy
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Git sync engine replicating the user's sync-git.sh behavior:
 * `git add -A` + auto commit when dirty, `git -c merge.default=union pull
 * --no-rebase --no-edit`, then push only if ahead of upstream.
 *
 * JGit has no union merge strategy; conflicts are resolved by concatenating
 * ours + theirs, which is deterministic and safe for append-heavy org files.
 */
@Singleton
class JGitSyncDataSource @Inject constructor(
    private val configStore: GitSyncConfigStore,
    private val credentialStore: GitCredentialStore,
    private val repoPathResolver: RepoPathResolver
) {
    private val syncMutex = Mutex()

    suspend fun sync(): Result<GitSyncOutcome> = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val root = repoPathResolver.resolveRepoRoot().getOrThrow()
                val outcome = Git.open(root).use { git -> doSync(git) }
                Result.success(outcome)
            } catch (e: Exception) {
                Result.failure(mapException(e))
            }
        }
    }

    /** Verifies the configured remote URL is reachable with the stored credentials. */
    suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = configStore.getRemoteUrl()
                ?: throw IllegalStateException("Remote URL is not configured")
            val credentials = credentials()
            Git.lsRemoteRepository()
                .setRemote(url)
                .setCredentialsProvider(credentials)
                .call()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(mapException(e))
        }
    }

    private fun doSync(git: Git): GitSyncOutcome {
        val repo = git.repository
        ensureIdentity(repo)

        val remoteName = resolveRemoteName(repo)
        val remoteUrl = repo.config.getString(
            ConfigConstants.CONFIG_REMOTE_SECTION, remoteName, ConfigConstants.CONFIG_KEY_URL
        )
            ?: configStore.getRemoteUrl()
            ?: throw IllegalStateException("No remote URL configured for '$remoteName'")

        var committed = false
        val status = git.status().call()
        if (!status.isClean) {
            // git add -A: stage additions/modifications, then update stage (deletions)
            git.add().addFilepattern(".").call()
            git.add().addFilepattern(".").setUpdate(true).call()
            git.commit()
                .setMessage(PHONE_COMMIT_MESSAGE)
                .setAuthor(GIT_USER_NAME, GIT_USER_EMAIL)
                .setCommitter(GIT_USER_NAME, GIT_USER_EMAIL)
                .call()
            committed = true
        }

        val credentials = credentials()
        ensureFetchRefspec(repo, remoteName)
        git.fetch()
            .setRemote(remoteName)
            .setCredentialsProvider(credentials)
            .call()

        val branch = currentBranch(repo)
            ?: throw IllegalStateException("Repository is in detached HEAD state; cannot sync")
        val upstream = resolveUpstream(repo, branch)
            ?: throw IllegalStateException(
                "No upstream tracking branch for '$branch'. Push once to establish it."
            )

        val localHead = repo.resolve(Constants.HEAD)
            ?: throw IllegalStateException("Repository has no commits yet")
        val remoteTip = repo.resolve(upstream)
            ?: throw IllegalStateException("Upstream '$upstream' not found on remote")

        val behind = countNotReachableFrom(repo, remoteTip, localHead)
        var merged = false
        val conflicted = mutableListOf<String>()
        if (behind > 0) {
            val mergeBranch = Repository.shortenRefName(upstream)
            val mergeMessage = "Merge branch '$mergeBranch' of $remoteUrl into '$branch'"
            val mergeResult = git.merge()
                .include(remoteTip)
                .setStrategy(MergeStrategy.RECURSIVE)
                .setMessage(mergeMessage)
                .call()

            when (mergeResult.mergeStatus) {
                MergeResult.MergeStatus.MERGED,
                MergeResult.MergeStatus.FAST_FORWARD -> merged = true

                MergeResult.MergeStatus.CONFLICTING -> {
                    conflicted.addAll(resolveConflictsByUnion(git, mergeResult))
                    git.commit().setMessage(mergeMessage).call()
                    merged = true
                }

                MergeResult.MergeStatus.ALREADY_UP_TO_DATE -> Unit

                else -> throw IllegalStateException(
                    "Merge failed: ${mergeResult.mergeStatus.name}"
                )
            }
        }

        val upstreamAfter = resolveUpstream(repo, branch)
        var pushed = false
        if (upstreamAfter == null) {
            pushAndCheck(git, remoteName, credentials)
            // Establish upstream tracking (equivalent of push -u) for future syncs
            val config = repo.config
            config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, branch, ConfigConstants.CONFIG_KEY_REMOTE, remoteName)
            config.setString(
                ConfigConstants.CONFIG_BRANCH_SECTION, branch, ConfigConstants.CONFIG_KEY_MERGE,
                Constants.R_HEADS + branch
            )
            config.save()
            pushed = true
        } else {
            val upstreamId = repo.resolve(upstreamAfter)
            val newHead = repo.resolve(Constants.HEAD)
            val aheadNow = if (upstreamId != null && newHead != null) {
                countNotReachableFrom(repo, newHead, upstreamId)
            } else {
                0
            }
            if (aheadNow > 0) {
                pushAndCheck(git, remoteName, credentials)
                pushed = true
            }
        }

        configStore.setLastSyncTime(System.currentTimeMillis())
        return GitSyncOutcome(
            committed = committed,
            merged = merged,
            pushed = pushed,
            conflictedFiles = conflicted
        )
    }

    private fun pushAndCheck(
        git: Git,
        remoteName: String,
        credentials: UsernamePasswordCredentialsProvider
    ) {
        val results = git.push()
            .setRemote(remoteName)
            .setCredentialsProvider(credentials)
            .call()
        val rejected = results.flatMap { it.remoteUpdates }
            .firstOrNull {
                it.status != RemoteRefUpdate.Status.OK &&
                    it.status != RemoteRefUpdate.Status.UP_TO_DATE
            }
        if (rejected != null) {
            throw IllegalStateException(
                "Push rejected (${rejected.status}): ${rejected.message ?: "no details"}"
            )
        }
    }

    /**
     * Union-merge stand-in for `merge.default=union`: for each conflicted path,
     * concatenates ours (stage 2) + theirs (stage 3) into the working file and
     * stages it.
     */
    private fun resolveConflictsByUnion(git: Git, mergeResult: MergeResult): List<String> {
        val repo = git.repository
        val dirCache = repo.readDirCache()
        val workTree = repo.workTree
        val resolved = mutableListOf<String>()

        for (path in mergeResult.conflicts.keys) {
            var ours: ByteArray? = null
            var theirs: ByteArray? = null
            for (i in 0 until dirCache.entryCount) {
                val e = dirCache.getEntry(i)
                if (e.pathString != path) continue
                when (e.stage) {
                    2 -> ours = repo.open(e.objectId).bytes
                    3 -> theirs = repo.open(e.objectId).bytes
                }
            }

            val union = unionBytes(ours, theirs)
                ?: continue // nothing resolvable (e.g. conflict without staged content)
            val outFile = File(workTree, path)
            outFile.parentFile?.mkdirs()
            outFile.writeBytes(union)
            git.add().addFilepattern(path).call()
            resolved.add(path)
        }
        return resolved
    }

    private fun unionBytes(ours: ByteArray?, theirs: ByteArray?): ByteArray? {
        if (ours != null && theirs != null) {
            if (ours.contentEquals(theirs)) return ours
            val separator =
                if (ours.isNotEmpty() && ours[ours.size - 1] == '\n'.code.toByte()) {
                    ByteArray(0)
                } else {
                    byteArrayOf('\n'.code.toByte())
                }
            return ours + separator + theirs
        }
        return ours ?: theirs
    }

    private fun ensureIdentity(repo: Repository) {
        val config = repo.config
        if (config.getString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_NAME) == null ||
            config.getString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_EMAIL) == null
        ) {
            config.setString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_NAME, GIT_USER_NAME)
            config.setString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_EMAIL, GIT_USER_EMAIL)
            config.save()
        }
    }

    /**
     * Resolves the remote to use: the branch's configured remote, else "origin"
     * (written into the repo config from app settings when missing), else fails.
     */
    private fun resolveRemoteName(repo: Repository): String {
        val branch = currentBranch(repo)
        val branchRemote = branch?.let {
            repo.config.getString(ConfigConstants.CONFIG_BRANCH_SECTION, it, ConfigConstants.CONFIG_KEY_REMOTE)
        }
        if (branchRemote != null) return branchRemote

        val hasOrigin = repo.config
            .getSubsections(ConfigConstants.CONFIG_REMOTE_SECTION)
            .contains(DEFAULT_REMOTE_NAME)
        if (hasOrigin) return DEFAULT_REMOTE_NAME

        val url = configStore.getRemoteUrl()
            ?: throw IllegalStateException(
                "No git remote configured. Add a remote to the repo or set the URL in Sync settings."
            )
        val config = repo.config
        config.setString(ConfigConstants.CONFIG_REMOTE_SECTION, DEFAULT_REMOTE_NAME, ConfigConstants.CONFIG_KEY_URL, url)
        config.save()
        return DEFAULT_REMOTE_NAME
    }

    private fun currentBranch(repo: Repository): String? {
        val ref = repo.exactRef(Constants.HEAD) ?: return null
        if (ref.isSymbolic) return Repository.shortenRefName(ref.target.name)
        return null // detached HEAD
    }

    /**
     * A remote needs a fetch refspec (as `git clone` writes it) or JGit fails
     * with "Nothing to fetch". Adds the standard refspec when missing.
     */
    private fun ensureFetchRefspec(repo: Repository, remoteName: String) {
        val config = repo.config
        val existing = config.getStringList(
            ConfigConstants.CONFIG_REMOTE_SECTION, remoteName, "fetch"
        )
        if (existing.isEmpty()) {
            config.setStringList(
                ConfigConstants.CONFIG_REMOTE_SECTION, remoteName, "fetch",
                listOf("+refs/heads/*:refs/remotes/$remoteName/*")
            )
            config.save()
        }
    }

    /** Mirrors `git rev-parse --abbrev-ref @{u}`. */    private fun resolveUpstream(repo: Repository, branch: String): String? {
        val config = repo.config
        val remote = config.getString(ConfigConstants.CONFIG_BRANCH_SECTION, branch, ConfigConstants.CONFIG_KEY_REMOTE)
            ?: return null
        val merge = config.getString(ConfigConstants.CONFIG_BRANCH_SECTION, branch, ConfigConstants.CONFIG_KEY_MERGE)
            ?: return null
        return "refs/remotes/$remote/${Repository.shortenRefName(merge)}"
    }

    /** Number of commits reachable from [includeFrom] but not from [uninteresting]. */
    private fun countNotReachableFrom(repo: Repository, includeFrom: ObjectId, uninteresting: ObjectId): Int {
        RevWalk(repo).use { walk ->
            walk.markStart(walk.parseCommit(includeFrom))
            walk.markUninteresting(walk.parseCommit(uninteresting))
            var count = 0
            while (walk.next() != null) count++
            return count
        }
    }

    /** Lightweight branch/ahead/behind/dirty state for the UI. */
    suspend fun getShortStatus(): GitRepoSnapshot? = withContext(Dispatchers.IO) {
        val root = repoPathResolver.resolveRepoRoot().getOrNull() ?: return@withContext null
        try {
            Git.open(root).use { git ->
                val repo = git.repository
                val branch = currentBranch(repo)
                val upstream = branch?.let { resolveUpstream(repo, it) }
                val head = repo.resolve(Constants.HEAD)
                val upstreamId = upstream?.let { repo.resolve(it) }
                var ahead = 0
                var behind = 0
                if (head != null && upstreamId != null) {
                    ahead = countNotReachableFrom(repo, head, upstreamId)
                    behind = countNotReachableFrom(repo, upstreamId, head)
                }
                GitRepoSnapshot(
                    repoRoot = root.absolutePath,
                    branch = branch,
                    ahead = ahead,
                    behind = behind,
                    dirty = !git.status().call().isClean,
                    hasUpstream = upstream != null
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun credentials(): UsernamePasswordCredentialsProvider {
        val (user, token) = credentialStore.getCredentials()
            ?: throw IllegalStateException("Git credentials not configured. Set username and token in Sync settings.")
        return UsernamePasswordCredentialsProvider(user, token)
    }

    private fun mapException(e: Throwable): Exception = when {
        e is IllegalStateException -> e
        e.message?.contains("401", ignoreCase = true) == true ||
            e.message?.contains("403", ignoreCase = true) == true ->
            IllegalStateException("Authentication failed - check username/token", e)
        e.message?.contains("not a git", ignoreCase = true) == true ->
            IllegalStateException("Not a git repository", e)
        e.message?.contains("UnknownHost", ignoreCase = true) == true ||
            e.message?.contains("Unable to resolve host", ignoreCase = true) == true ||
            e.message?.contains("connection", ignoreCase = true) == true ->
            IllegalStateException("Network error: ${e.message}", e)
        e is GitAPIException -> IllegalStateException("Git error: ${e.message}", e)
        e is Exception -> e
        else -> IllegalStateException(e.message ?: "Git sync failed", e)
    }

    companion object {
        const val GIT_USER_NAME = "org-util"
        const val GIT_USER_EMAIL = "org-util@localhost"
        const val PHONE_COMMIT_MESSAGE = "(phone)Auto commit: save local modifications"
        private const val DEFAULT_REMOTE_NAME = "origin"
    }
}
