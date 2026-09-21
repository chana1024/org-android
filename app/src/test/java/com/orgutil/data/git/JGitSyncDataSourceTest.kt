package com.orgutil.data.git

import com.orgutil.data.datasource.GitCredentialStore
import com.orgutil.data.datasource.GitSyncConfigStore
import com.orgutil.domain.sync.GitSyncOutcome
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import java.io.File

/**
 * Exercises [JGitSyncDataSource.sync] against real JGit repositories with a
 * file:// remote, covering the sync-git.sh script behavior: dirty auto-commit,
 * clean no-op, merge of diverging changes, and union conflict resolution.
 */
class JGitSyncDataSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var serverDir: File
    private lateinit var workDir: File
    private lateinit var dataSource: JGitSyncDataSource

    private val configStore: GitSyncConfigStore = Mockito.mock(GitSyncConfigStore::class.java)
    private val credentialStore: GitCredentialStore = Mockito.mock(GitCredentialStore::class.java)
    private val repoPathResolver: RepoPathResolver = Mockito.mock(RepoPathResolver::class.java)

    @Before
    fun setUp() {
        serverDir = tmp.newFolder("server")
        workDir = tmp.newFolder("work")

        // Bare "remote" server repo
        Git.init().setDirectory(serverDir).setBare(true).call().use { bare ->
            bare.repository.config.setString(
                ConfigConstants.CONFIG_REMOTE_SECTION, "origin", "url", serverDir.toURI().toString()
            )
            bare.repository.config.save()
        }

        // Local repo with one commit, tracking origin/master
        Git.init().setDirectory(workDir).call().use { git ->
            git.repository.config.setString(
                ConfigConstants.CONFIG_REMOTE_SECTION, "origin", "url", serverDir.toURI().toString()
            )
            git.repository.config.save()
            commitFile(git, "notes.org", "hello\n")
            git.repository.config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, "master", ConfigConstants.CONFIG_KEY_REMOTE, "origin")
            git.repository.config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, "master", ConfigConstants.CONFIG_KEY_MERGE, Constants.R_HEADS + "master")
            git.repository.config.save()
            // Seed the server so the branch is not unborn
            git.push().setRemote("origin").call()
        }

        Mockito.doReturn("user" to "token").`when`(credentialStore).getCredentials()
        Mockito.doNothing().`when`(configStore).setLastSyncTime(Mockito.anyLong())

        runTest {
            Mockito.`when`(repoPathResolver.resolveRepoRoot()).thenReturn(Result.success(workDir))
        }

        dataSource = JGitSyncDataSource(configStore, credentialStore, repoPathResolver)
    }

    /** Each test stubs the suspend resolver inside a coroutine context. */
    private fun stubRepoRoot() = runTest {
        Mockito.`when`(repoPathResolver.resolveRepoRoot()).thenReturn(Result.success(workDir))
    }

    private fun commitFile(git: Git, path: String, content: String) {
        File(git.repository.workTree, path).apply {
            parentFile?.mkdirs()
            writeText(content)
        }
        git.add().addFilepattern(path).call()
        git.commit()
            .setMessage("setup commit")
            .setAuthor("tester", "tester@test")
            .setCommitter("tester", "tester@test")
            .call()
    }

    private fun readWorkFile(path: String): String = File(workDir, path).readText()

    @Test
    fun `dirty working tree is committed and pushed`() = runTest {
        stubRepoRoot()
        File(workDir, "notes.org").writeText("hello\nworld\n")

        val result = dataSource.sync()

        assertTrue("sync failed: ${result.exceptionOrNull()?.message ?: result}", result.isSuccess)
        val outcome = result.getOrThrow() as GitSyncOutcome
        assertTrue(outcome.committed)
        assertTrue(outcome.pushed)
        // Server should now hold the auto commit
        Git.cloneRepository().setURI(serverDir.toURI().toString()).setDirectory(tmp.newFolder("clone1")).call().use { clone ->
            val log = clone.log().setMaxCount(1).call().first()
            assertEquals(JGitSyncDataSource.PHONE_COMMIT_MESSAGE, log.fullMessage)
            assertEquals("hello\nworld\n", File(clone.repository.workTree, "notes.org").readText())
        }
    }

    @Test
    fun `clean tree with nothing ahead does not push`() = runTest {
        stubRepoRoot()
        val result = dataSource.sync()

        assertTrue("sync failed: ${result.exceptionOrNull()?.message ?: result}", result.isSuccess)
        val outcome = result.getOrThrow()
        assertFalse(outcome.committed)
        assertFalse(outcome.pushed)
    }

    @Test
    fun `diverging remote change on another file merges`() = runTest {
        stubRepoRoot()
        // Add a remote-only commit touching a different file
        Git.cloneRepository().setURI(serverDir.toURI().toString())
            .setDirectory(tmp.newFolder("other")).call().use { other ->
                commitFile(other, "other.org", "remote content\n")
                other.push().setRemote("origin").call()
            }

        val result = dataSource.sync()

        assertTrue("sync failed: ${result.exceptionOrNull()?.message ?: result}", result.isSuccess)
        val outcome = result.getOrThrow()
        // Pure fast-forward: merged locally but no local commits to push
        assertTrue(outcome.merged)
        assertFalse(outcome.pushed)
        assertEquals("remote content\n", readWorkFile("other.org"))
    }

    @Test
    fun `conflicting edits to the same file are union merged`() = runTest {
        stubRepoRoot()
        // Diverge: ours edits notes.org locally (committed)
        Git.open(workDir).use { git ->
            commitFile(git, "notes.org", "ours line\n")
        }
        // theirs edits notes.org on the server from the original base
        Git.cloneRepository().setURI(serverDir.toURI().toString())
            .setDirectory(tmp.newFolder("other")).call().use { other ->
                commitFile(other, "notes.org", "theirs line\n")
                other.push().setRemote("origin").call()
            }

        val result = dataSource.sync()

        assertTrue("sync failed: ${result.exceptionOrNull()?.message ?: result}", result.isSuccess)
        val outcome = result.getOrThrow()
        assertTrue(outcome.merged)
        assertTrue(outcome.conflictedFiles.contains("notes.org"))
        assertTrue(outcome.pushed)
        val content = readWorkFile("notes.org")
        assertTrue("union should contain ours", content.contains("ours line"))
        assertTrue("union should contain theirs", content.contains("theirs line"))
    }

    @Test
    fun `uncommitted local edits survive conflict union`() = runTest {
        stubRepoRoot()
        // theirs changes notes.org on the server
        Git.cloneRepository().setURI(serverDir.toURI().toString())
            .setDirectory(tmp.newFolder("other")).call().use { other ->
                commitFile(other, "notes.org", "hello\nremote edit\n")
                other.push().setRemote("origin").call()
            }
        // ours has an UNCOMMITTED local edit to the same file
        File(workDir, "notes.org").writeText("hello\nlocal edit\n")

        val result = dataSource.sync()

        assertTrue("sync failed: ${result.exceptionOrNull()?.message ?: result}", result.isSuccess)
        val outcome = result.getOrThrow()
        // Script behavior: local edit gets committed first, so the merge then
        // conflicts and is resolved by union
        assertTrue(outcome.committed)
        val content = readWorkFile("notes.org")
        assertTrue(content.contains("local edit"))
        assertTrue(content.contains("remote edit"))
    }
}
