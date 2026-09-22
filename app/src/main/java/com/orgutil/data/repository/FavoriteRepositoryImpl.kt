package com.orgutil.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import com.orgutil.data.datasource.DocumentTreeStore
import com.orgutil.di.IoDispatcher
import com.orgutil.domain.repository.FavoriteRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Favorites are local app state (product decision C): they live in shared
 * preferences and are never written into the user's notes repository.
 *
 * The pre-existing behavior stored favorites in a `.orgutil_favorites` file
 * inside the document tree (and thereby in the user's git repo). Legacy
 * files are migrated once per tree: their entries are union-merged into the
 * stored set. The legacy file itself is never modified or deleted, and no
 * favorites file is ever created again.
 */
@Singleton
class FavoriteRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documentTreeStore: DocumentTreeStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : FavoriteRepository {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun addToFavorites(fileUri: Uri): Unit = withContext(ioDispatcher) {
        updateFavorites { it.add(fileUri.toString()) }
    }

    override suspend fun removeFromFavorites(fileUri: Uri): Unit = withContext(ioDispatcher) {
        updateFavorites { it.remove(fileUri.toString()) }
    }

    override suspend fun isFavorite(fileUri: Uri): Boolean = withContext(ioDispatcher) {
        getFavoriteUris().contains(fileUri.toString())
    }

    override fun getFavoriteUrisFlow(): Flow<Set<String>> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == FAVORITES_KEY || key == FAVORITES_VERSION_KEY) {
                trySend(storedFavorites())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(storedFavorites())
        // Complete the one-time legacy import from the flow as well, without
        // blocking the collector (no runBlocking on the main thread).
        val importJob = launch {
            importLegacyFavoritesIfNeeded()
            trySend(storedFavorites())
        }
        awaitClose {
            importJob.cancel()
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    override suspend fun getFavoriteUris(): Set<String> = withContext(ioDispatcher) {
        importLegacyFavoritesIfNeeded()
        storedFavorites()
    }

    override suspend fun clearFavorites(): Unit = withContext(ioDispatcher) {
        updateFavorites { it.clear() }
    }

    /**
     * Applies [transform] to a copy of the stored set and persists it,
     * bumping [FAVORITES_VERSION_KEY] so observers refresh.
     */
    private fun updateFavorites(transform: (MutableSet<String>) -> Unit) {
        val updated = storedFavorites().toMutableSet()
        transform(updated)
        prefs.edit {
            putStringSet(FAVORITES_KEY, updated)
            putLong(FAVORITES_VERSION_KEY, prefs.getLong(FAVORITES_VERSION_KEY, 0) + 1)
        }
    }

    private fun storedFavorites(): Set<String> =
        prefs.getStringSet(FAVORITES_KEY, emptySet())?.toSet() ?: emptySet()

    /**
     * One-time (per stored tree) import of the legacy `.orgutil_favorites`
     * file. Idempotent: guarded by a marker preference storing the tree the
     * import ran for, so entries removed after the import are never
     * resurrected by a repeat. The legacy file is read-only here.
     */
    private suspend fun importLegacyFavoritesIfNeeded() {
        val treeUri = documentTreeStore.getStoredTreeUri() ?: return
        val treeUriString = treeUri.toString()
        if (prefs.getString(LEGACY_IMPORTED_TREE_KEY, null) == treeUriString) return

        val legacyEntries = readLegacyFavoritesFile(treeUri)
        if (legacyEntries.isNotEmpty()) {
            val merged = storedFavorites().toMutableSet()
            merged.addAll(legacyEntries)
            prefs.edit {
                putStringSet(FAVORITES_KEY, merged)
                putLong(FAVORITES_VERSION_KEY, prefs.getLong(FAVORITES_VERSION_KEY, 0) + 1)
            }
        }
        prefs.edit { putString(LEGACY_IMPORTED_TREE_KEY, treeUriString) }
    }

    /** Reads the legacy favorites file if it exists; returns null-safe entries. Never writes it. */
    private fun readLegacyFavoritesFile(treeUri: Uri): Set<String> {
        return try {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptySet()
            if (!root.exists() || !root.isDirectory) return emptySet()
            val legacyFile = root.listFiles().firstOrNull { file ->
                file.isFile && file.name != null && isLegacyFavoritesFileName(file.name!!)
            } ?: return emptySet()
            context.contentResolver.openInputStream(legacyFile.uri)?.use { inputStream ->
                inputStream.bufferedReader(Charsets.UTF_8)
                    .readLines()
                    .filter { it.isNotBlank() }
                    .toSet()
            } ?: emptySet()
        } catch (e: Exception) {
            logE("Legacy favorites import failed; skipping", e)
            emptySet()
        }
    }

    /** android.util.Log is unimplemented in JVM unit tests; never let logging throw. */
    private fun logE(message: String, throwable: Throwable? = null) {
        runCatching {
            if (throwable != null) android.util.Log.e(TAG, message, throwable)
            else android.util.Log.e(TAG, message)
        }
    }

    private fun isLegacyFavoritesFileName(name: String): Boolean =
        name == FAVORITES_FILE_NAME ||
            name.startsWith(FAVORITES_FILE_NAME) ||
            (name.contains("orgutil") && name.contains("favorites"))

    private companion object {
        const val TAG = "FavoriteRepository"
        const val PREFS_NAME = "org_util_prefs"
        const val FAVORITES_KEY = "favorite_uris"
        const val FAVORITES_VERSION_KEY = "favorites_version"
        const val LEGACY_IMPORTED_TREE_KEY = "favorites_legacy_imported_tree"
        const val FAVORITES_FILE_NAME = ".orgutil_favorites"
    }
}
