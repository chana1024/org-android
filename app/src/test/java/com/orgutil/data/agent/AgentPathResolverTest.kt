package com.orgutil.data.agent

import androidx.documentfile.provider.DocumentFile
import com.orgutil.data.datasource.DocumentTreeStore
import com.orgutil.domain.chat.ToolArgumentException
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Pins the pure path validation of the harness boundary. These rules hold
 * in APPROVAL and AUTO modes alike - they are the structural domain wall,
 * not approvals.
 */
class AgentPathResolverTest {

    private val treeStore = mock(DocumentTreeStore::class.java)

    private val resolver = AgentPathResolver(
        documentTreeStore = treeStore,
        ioDispatcher = UnconfinedTestDispatcher()
    )

    private fun segments(path: String): List<String> = resolver.normalizeAndValidate(path)

    @Test
    fun `normalizes simple and nested paths`() {
        assertEquals(listOf("gtd.org"), segments("gtd.org"))
        assertEquals(listOf("notes", "gtd.org"), segments("/notes/gtd.org"))
        assertEquals(listOf("notes", "projects", "a.org_archive"), segments("notes//projects/a.org_archive"))
    }

    @Test
    fun `rejects traversal`() {
        for (bad in listOf("../x.org", "notes/../../x.org", "..", "a/../b.org")) {
            try {
                segments(bad)
                fail("expected rejection: $bad")
            } catch (expected: ToolArgumentException) {
            }
        }
    }

    @Test
    fun `rejects git internals and legacy favorites`() {
        for (bad in listOf(".git/config.org", "notes/.git/x.org", ".orgutil_favorites", "notes/.orgutil_favorites2.org")) {
            try {
                segments(bad)
                fail("expected rejection: $bad")
            } catch (expected: ToolArgumentException) {
            }
        }
    }

    @Test
    fun `rejects hidden files and non-org extensions`() {
        for (bad in listOf("notes/.secret.org", "notes/picture.png", "notes/readme.md")) {
            try {
                segments(bad)
                fail("expected rejection: $bad")
            } catch (expected: ToolArgumentException) {
            }
        }
    }

    @Test
    fun `rejects encoded separators and empty paths`() {
        for (bad in listOf("notes%2Fx.org", "notes\\x.org", "", "   ", "///")) {
            try {
                segments(bad)
                fail("expected rejection: '$bad'")
            } catch (expected: ToolArgumentException) {
            }
        }
    }

    @Test
    fun `creation validation relaxes the extension but keeps the structural rules`() {
        // Extension may be omitted (documented org_create_file behavior)...
        assertEquals(listOf("notes", "shopping"), resolver.normalizeAndValidate("notes/shopping", requireOrgExtension = false))
        assertEquals(listOf("arch.org_archive"), resolver.normalizeAndValidate("arch.org_archive", false))
        // ...traversal, hidden, git and favorites names are still rejected.
        for (bad in listOf("../evil", "notes/../.secret", ".git/x", ".orgutil_favorites", "notes/.hidden")) {
            try {
                resolver.normalizeAndValidate(bad, requireOrgExtension = false)
                fail("expected rejection: $bad")
            } catch (expected: ToolArgumentException) {
            }
        }
    }

    @Test
    fun `resolveParentForCreation appends the org suffix`() = runTest(UnconfinedTestDispatcher()) {
        val root = mock(DocumentFile::class.java)
        `when`(treeStore.requireTreeDocumentFile()).thenReturn(root)
        `when`(root.isDirectory).thenReturn(true)

        val (parent, name) = resolver.resolveParentForCreation("shopping")
        assertEquals(root, parent)
        assertEquals("shopping.org", name)

        val (_, archiveName) = resolver.resolveParentForCreation("arch.org_archive")
        assertEquals("arch.org_archive", archiveName)
    }

    @Test
    fun `resolveParentForCreation rejects a missing parent directory`() = runTest(UnconfinedTestDispatcher()) {
        val root = mock(DocumentFile::class.java)
        `when`(treeStore.requireTreeDocumentFile()).thenReturn(root)
        `when`(root.isDirectory).thenReturn(true)
        `when`(root.findFile("missing")).thenReturn(null)

        try {
            resolver.resolveParentForCreation("missing/shopping")
            fail("expected rejection for missing parent")
        } catch (expected: ToolArgumentException) {
        }
    }

    @Test
    fun `validateFileName accepts only bare org names`() {
        resolver.validateFileName("renamed.org")
        resolver.validateFileName("Renamed.Org")
        resolver.validateFileName("arch.org_archive")
        for (bad in listOf(
            "noext", "sub/renamed.org", "sub\\renamed.org", "sub%2Frenamed.org",
            ".hidden.org", ".gitignore.org", ".orgutil_favorites.org", "", "  ", "notes.org/"
        )) {
            try {
                resolver.validateFileName(bad)
                fail("expected rejection: '$bad'")
            } catch (expected: ToolArgumentException) {
            }
        }
    }
}
