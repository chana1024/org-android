package com.orgutil.data.repository

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.orgutil.data.datasource.DocumentTreeStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anySet
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.io.ByteArrayInputStream

/**
 * Decision C: favorites are local app state.
 * - adding/removing favorites never touches any file in the user's repo;
 * - the legacy `.orgutil_favorites` file is imported exactly once per tree,
 *   merged (union) into the stored set, and never written or deleted;
 * - a favorite removed after the import is not resurrected by a repeat.
 */
class FavoriteRepositoryImplTest {

    @Mock lateinit var context: Context
    @Mock lateinit var documentTreeStore: DocumentTreeStore
    @Mock lateinit var prefs: SharedPreferences
    @Mock lateinit var editor: SharedPreferences.Editor
    @Mock lateinit var contentResolver: ContentResolver

    private val treeUri: Uri = mock(Uri::class.java)

    private lateinit var repository: FavoriteRepositoryImpl

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        `when`(context.getSharedPreferences("org_util_prefs", Context.MODE_PRIVATE)).thenReturn(prefs)
        `when`(context.contentResolver).thenReturn(contentResolver)
        `when`(prefs.edit()).thenReturn(editor)
        `when`(editor.putString(anyString(), anyString())).thenReturn(editor)
        `when`(editor.putStringSet(anyString(), anySet())).thenReturn(editor)
        `when`(editor.putLong(anyString(), anyLong())).thenReturn(editor)
        `when`(treeUri.toString()).thenReturn("content://tree/notes")
        `when`(documentTreeStore.getStoredTreeUri()).thenReturn(treeUri)
        // Unconfined keeps withContext(ioDispatcher) on the test thread, where
        // the thread-local mockStatic registration is visible.
        repository = FavoriteRepositoryImpl(context, documentTreeStore, UnconfinedTestDispatcher())
    }

    @Test
    fun `legacy favorites file is imported once and never written`() = runTest {
        `when`(prefs.getStringSet(eq("favorite_uris"), any())).thenReturn(emptySet())
        `when`(prefs.getString(eq("favorites_legacy_imported_tree"), anyString())).thenReturn(null)
        `when`(prefs.getLong(eq("favorites_version"), anyLong())).thenReturn(0L)

        val root = treeRootWithLegacyFile()
        mockStatic(DocumentFile::class.java).use { mockedStatic ->
            mockedStatic.`when`<DocumentFile> { DocumentFile.fromTreeUri(any(Context::class.java), any()) }
                .thenReturn(root)
            repository.getFavoriteUris()
        }

        // The mock prefs do not persist writes, so assert on what was stored.
        verify(editor).putStringSet(
            eq("favorite_uris"),
            eq(setOf("content://tree/notes/a.org", "content://tree/notes/b.org"))
        )
        // The legacy file was read but never opened for writing/deleting.
        verify(contentResolver, never()).openOutputStream(any(), anyString())
        // The import is recorded as done for this tree.
        verify(editor).putString("favorites_legacy_imported_tree", "content://tree/notes")
    }

    @Test
    fun `repeat after import does not resurrect removed favorites`() = runTest {
        // Simulates a later app run: import marker already set, and the user
        // has since removed b.org from the stored set.
        `when`(prefs.getString(eq("favorites_legacy_imported_tree"), anyString()))
            .thenReturn("content://tree/notes")
        `when`(prefs.getStringSet(eq("favorite_uris"), any()))
            .thenReturn(setOf("content://tree/notes/a.org"))

        val favorites = repository.getFavoriteUris()

        assertEquals(setOf("content://tree/notes/a.org"), favorites)
        // The legacy file is not even read again, and never written.
        verify(contentResolver, never()).openInputStream(any())
        verify(contentResolver, never()).openOutputStream(any(), anyString())
    }

    @Test
    fun `adding and removing favorites only touches prefs`() = runTest {
        `when`(prefs.getString(eq("favorites_legacy_imported_tree"), anyString()))
            .thenReturn("content://tree/notes")
        `when`(prefs.getStringSet(eq("favorite_uris"), any()))
            .thenReturn(setOf("content://tree/notes/a.org"))
        `when`(prefs.getLong(eq("favorites_version"), anyLong())).thenReturn(7L)

        val fileUri = mock(Uri::class.java)
        `when`(fileUri.toString()).thenReturn("content://tree/notes/c.org")

        repository.addToFavorites(fileUri)

        verify(editor).putStringSet(
            eq("favorite_uris"),
            eq(setOf("content://tree/notes/a.org", "content://tree/notes/c.org"))
        )
        verify(editor).putLong("favorites_version", 8L)
        verify(contentResolver, never()).openOutputStream(any(), anyString())

        repository.removeFromFavorites(fileUri)

        verify(editor).putStringSet(eq("favorite_uris"), eq(setOf("content://tree/notes/a.org")))
        verify(contentResolver, never()).openOutputStream(any(), anyString())
    }

    @Test
    fun `flow emits stored favorites without blocking on legacy import`() = runTest {
        `when`(prefs.getString(eq("favorites_legacy_imported_tree"), anyString()))
            .thenReturn("content://tree/notes")
        `when`(prefs.getStringSet(eq("favorite_uris"), any()))
            .thenReturn(setOf("content://tree/notes/a.org"))

        val emitted = repository.getFavoriteUrisFlow().first()

        assertEquals(setOf("content://tree/notes/a.org"), emitted)
    }

    @Test
    fun `legacy import failure still marks the tree and keeps stored favorites`() = runTest {
        `when`(prefs.getStringSet(eq("favorite_uris"), any()))
            .thenReturn(setOf("content://tree/notes/a.org"))
        `when`(prefs.getString(eq("favorites_legacy_imported_tree"), anyString())).thenReturn(null)
        `when`(prefs.getLong(eq("favorites_version"), anyLong())).thenReturn(0L)

        val root = treeRootWithLegacyFile()
        // The stream breaks mid-import; the import must not throw and must
        // not lose stored favorites. (openInputStream declares
        // FileNotFoundException, so Mockito only accepts that type.)
        `when`(contentResolver.openInputStream(any())).thenThrow(java.io.FileNotFoundException("broken"))
        val favorites: Set<String>
        mockStatic(DocumentFile::class.java).use { mockedStatic ->
            mockedStatic.`when`<DocumentFile> { DocumentFile.fromTreeUri(any(Context::class.java), any()) }
                .thenReturn(root)
            favorites = repository.getFavoriteUris()
        }

        assertEquals(setOf("content://tree/notes/a.org"), favorites)
        verify(editor).putString("favorites_legacy_imported_tree", "content://tree/notes")
    }

    /** Tree root containing only the legacy favorites file. */
    private fun treeRootWithLegacyFile(): DocumentFile {
        val root = mock(DocumentFile::class.java)
        val legacyFile = mock(DocumentFile::class.java)
        val legacyUri = mock(Uri::class.java)
        `when`(legacyFile.name).thenReturn(".orgutil_favorites")
        `when`(legacyFile.isFile).thenReturn(true)
        `when`(legacyFile.uri).thenReturn(legacyUri)
        `when`(root.exists()).thenReturn(true)
        `when`(root.isDirectory).thenReturn(true)
        `when`(root.listFiles()).thenReturn(arrayOf(legacyFile))
        `when`(contentResolver.openInputStream(legacyUri)).thenReturn(
            ByteArrayInputStream("content://tree/notes/a.org\ncontent://tree/notes/b.org\n".toByteArray())
        )
        return root
    }
}
