package com.orgutil.data.repository

import android.net.Uri
import android.util.Log
import com.orgutil.data.database.dao.FileDao
import com.orgutil.data.datasource.FileDataSource
import com.orgutil.domain.model.OrgFileInfo
import com.orgutil.domain.repository.FavoriteRepository
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
            val ftsQuery = prepareFtsQuery(query)
            return favoriteRepository.getFavoriteUrisFlow().combine(flowOf(Unit)) { favoriteUris, _ ->
                try {
                    val fileNameResults = fileDao.searchFilesByName(query)
                    val contentResults = fileDao.searchFilesByContent(ftsQuery)
                    val contentLikeResults = if (shouldUseContentLikeFallback(query)) {
                        fileDao.searchFilesByContentLike(query)
                    } else {
                        emptyList()
                    }
                    val scopedPaths = scopedPathsOrNull(uri)
                    val allResults = (fileNameResults + contentResults + contentLikeResults)
                        .distinctBy { it.path }
                        .let { results ->
                            if (scopedPaths == null) results else results.filter { it.path in scopedPaths }
                        }
                        .sortedBy { it.fileName }

                    allResults.map { metadata ->
                        val parsedUri = runCatching { Uri.parse(metadata.path) }
                            .getOrElse { Uri.fromFile(java.io.File(metadata.path)) }
                        OrgFileInfo(
                            uri = parsedUri,
                            name = metadata.fileName,
                            lastModified = metadata.lastModified,
                            isFavorite = favoriteUris.contains(metadata.path),
                            size = metadata.size,
                            isDirectory = false
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error searching files", e)
                    emptyList()
                }
            }
        }

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

    private suspend fun scopedPathsOrNull(uri: Uri?): Set<String>? {
        val currentUri = uri ?: return null
        val storedTreeUri = fileDataSource.getStoredTreeUri()?.toString()
        if (currentUri.toString() == storedTreeUri) return null
        return fileDataSource.getAllOrgFilesUnder(currentUri)
            .map { it.uri.toString() }
            .toSet()
    }

    private fun prepareFtsQuery(query: String): String {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) return trimmedQuery

        val containsChinese = trimmedQuery.any { it.toString().matches("[\\u4e00-\\u9fa5]".toRegex()) }
        return if (containsChinese) {
            val parts = trimmedQuery.split("\\s+".toRegex()).filter { it.isNotBlank() }
            parts.joinToString(" AND ") { part ->
                val escapedPart = part.replace("\"", "\"\"")
                "$escapedPart*"
            }
        } else {
            val words = trimmedQuery.split("\\s+".toRegex()).filter { it.isNotBlank() }
            if (words.size == 1) {
                val escapedWord = words[0].replace("\"", "\"\"")
                "$escapedWord*"
            } else {
                words.map { word -> "${word.replace("\"", "\"\"")}*" }.joinToString(" AND ")
            }
        }
    }

    private fun shouldUseContentLikeFallback(query: String): Boolean {
        return query.any { it.code > 127 || (!it.isLetterOrDigit() && !it.isWhitespace()) }
    }

    private companion object {
        const val TAG = "OrgFileQueryService"
    }
}
