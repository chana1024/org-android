package com.orgutil.data.repository

import android.net.Uri
import android.util.Log
import com.orgutil.data.database.dao.FileDao
import com.orgutil.data.datasource.FileDataSource
import com.orgutil.domain.model.OrgFileInfo
import com.orgutil.domain.repository.FavoriteRepository
import com.orgutil.domain.search.FtsQueryBuilder
import com.orgutil.domain.search.SearchPreviewBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OrgFileQueryService @Inject constructor(
    private val fileDao: FileDao,
    private val favoriteRepository: FavoriteRepository,
    private val fileDataSource: FileDataSource
) {
    fun observeOrgFiles(uri: Uri?, query: String?, useDatabase: Boolean): Flow<List<OrgFileInfo>> {
        if (!query.isNullOrBlank() && useDatabase) {
            // Full-text search: Room/FTS only, no SAF access on the keystroke
            // path. FtsQueryBuilder returns null when no term can ever match
            // (whitespace/punctuation only) - skip the query entirely.
            val ftsQuery = FtsQueryBuilder.buildMatchQuery(query)
                ?: return flowOf(emptyList())
            val scopedUri = scopedUriOrNull(uri)

            return favoriteRepository.getFavoriteUrisFlow().combine(flowOf(Unit)) { favoriteUris, _ ->
                // Metadata + content arrive joined in one query; previews are
                // built from the joined content (no per-hit lookups). Errors
                // propagate to the caller (the ViewModel surfaces them); no
                // catch-all masking.
                val results = fileDao.searchFilesWithContent(ftsQuery)
                results.asSequence()
                    .filter { scopedUri == null || isUnderTree(resultPath = it.path, treeUri = scopedUri) }
                    .mapNotNull { row ->
                        val parsedUri = runCatching { Uri.parse(row.path) }
                            .getOrElse { Uri.fromFile(java.io.File(row.path)) }
                        val preview = SearchPreviewBuilder.build(row.content, query)
                            ?: return@mapNotNull null
                        OrgFileInfo(
                            uri = parsedUri,
                            name = row.fileName,
                            lastModified = row.lastModified,
                            isFavorite = favoriteUris.contains(row.path),
                            size = row.size,
                            isDirectory = false,
                            searchPreview = preview.text,
                            searchPreviewMatchStart = preview.matchStart,
                            searchPreviewMatchLength = preview.matchLength,
                            searchMatchContentOffset = preview.contentOffset
                        )
                    }
                    .toList()
            }
        }

        // FILE_LIST mode: the data source matches names/relative paths only
        // and never reads file bodies (see OrgFileScanner).
        if (uri != null) {
            return favoriteRepository.getFavoriteUrisFlow().combine(flowOf(Unit)) { favoriteUris, _ ->
                try {
                    val files = fileDataSource.getOrgFiles(uri, query)
                    files.map { file -> file.copy(isFavorite = favoriteUris.contains(file.uri.toString())) }
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting files from FileDataSource for URI", e)
                    emptyList()
                }
            }
        }

        return favoriteRepository.getFavoriteUrisFlow().combine(flowOf(Unit)) { favoriteUris, _ ->
            try {
                val fileSourceFiles = fileDataSource.getOrgFiles(null, query)
                fileSourceFiles.map { file -> file.copy(isFavorite = favoriteUris.contains(file.uri.toString())) }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting files from FileDataSource", e)
                emptyList()
            }
        }
    }

    /** Null when the search is not scoped to a subtree (root or stored tree itself). */
    private fun scopedUriOrNull(uri: Uri?): Uri? {
        val currentUri = uri ?: return null
        val storedTreeUri = fileDataSource.getStoredTreeUri()?.toString()
        return if (currentUri.toString() == storedTreeUri) null else currentUri
    }

    /**
     * Prefix check on URI strings: document URIs enumerated from a SAF tree
     * always embed the tree URI as a literal prefix, followed by "/", "?",
     * or an encoded path separator ("%2F"). The separator boundary is what
     * keeps "notes" from matching "notes2".
     */
    private fun isUnderTree(resultPath: String, treeUri: Uri): Boolean {
        val prefix = treeUri.toString()
        if (!resultPath.startsWith(prefix, ignoreCase = true)) return false
        val remainder = resultPath.removePrefix(prefix)
        return remainder.startsWith("/") ||
            remainder.startsWith("?") ||
            remainder.startsWith("%2F", ignoreCase = true)
    }

    private companion object {
        const val TAG = "OrgFileQueryService"
    }
}
