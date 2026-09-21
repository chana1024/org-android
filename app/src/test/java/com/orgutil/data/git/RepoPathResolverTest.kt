package com.orgutil.data.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepoPathResolverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `discoverRepoRoot walks up to the nearest git dir`() {
        val repoRoot = tmp.newFolder("repo")
        File(repoRoot, ".git").mkdir()
        val nested = File(repoRoot, "notes/sub").apply { mkdirs() }

        assertEquals(repoRoot, RepoPathResolver.discoverRepoRoot(nested))
        assertEquals(repoRoot, RepoPathResolver.discoverRepoRoot(repoRoot))
    }

    @Test
    fun `discoverRepoRoot supports git file worktrees`() {
        val repoRoot = tmp.newFolder("repo")
        File(repoRoot, ".git").writeText("gitdir: /somewhere/else\n")
        val nested = File(repoRoot, "org").apply { mkdirs() }

        assertEquals(repoRoot, RepoPathResolver.discoverRepoRoot(nested))
    }

    @Test
    fun `discoverRepoRoot returns null when no repo exists`() {
        val plain = File(tmp.newFolder("plain"), "deep/deeper").apply { mkdirs() }
        assertNull(RepoPathResolver.discoverRepoRoot(plain))
    }
}
