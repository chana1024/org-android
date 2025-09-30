package com.orgutil.data.repository

import android.content.Context
import android.net.Uri
import com.orgutil.data.database.dao.FileDao
import com.orgutil.data.mapper.OrgParserWrapper
import com.orgutil.domain.model.OrgDocument
import com.orgutil.domain.model.OrgFileInfo
import com.orgutil.domain.repository.FavoriteRepository
import com.orgutil.domain.repository.OrgFileRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import com.orgutil.data.datasource.FileDataSource
import android.util.Log
import java.io.File

@Singleton
class OrgFileRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileDao: FileDao,
    private val orgParserWrapper: OrgParserWrapper,
    private val favoriteRepository: FavoriteRepository,
    private val fileDataSource: FileDataSource // Keep for file operations
) : OrgFileRepository {

    override suspend fun getOrgFiles(uri: Uri?, query: String?): Flow<List<OrgFileInfo>> {
        Log.d("OrgFileRepositoryImpl", "getOrgFiles called with uri: $uri, query: '$query'")

        // If a specific URI is provided, use FileDataSource directly for directory browsing
        if (uri != null) {
            Log.d("OrgFileRepositoryImpl", "Using FileDataSource for specific URI")
            val files = fileDataSource.getOrgFiles(uri, query)
            return flowOf(files)
        }

        // For search queries, prefer database search when available, but also include directories from FileDataSource
        if (!query.isNullOrBlank()) {
            Log.d("OrgFileRepositoryImpl", "Using database search for query: '$query'")
            // For FTS queries, we need to handle Chinese characters properly
            val ftsQuery = prepareFtsQuery(query)
            val filesFlow = fileDao.searchFilesByNameAndContent(query, ftsQuery)
            return filesFlow.combine(favoriteRepository.getFavoriteUrisFlow()) { files, favoriteUris ->
                val searchResults = files.map { metadata ->
                    val uri = try {
                        Uri.parse(metadata.path)
                    } catch (e: Exception) {
                        Log.e("OrgFileRepositoryImpl", "Failed to parse URI: ${metadata.path}", e)
                        Uri.fromFile(java.io.File(metadata.path))
                    }
                    OrgFileInfo(
                        uri = uri,
                        name = metadata.fileName,
                        lastModified = metadata.lastModified,
                        isFavorite = favoriteUris.contains(metadata.path),
                        size = metadata.size,
                        isDirectory = false
                    )
                }

                // When searching, also include matching directories from FileDataSource
                try {
                    val fileSourceResults = fileDataSource.getOrgFiles(null, query)
                    val directories = fileSourceResults.filter { it.isDirectory }
                    val allResults = directories + searchResults
                    allResults.sortedWith(compareByDescending<OrgFileInfo> { it.isDirectory }.thenBy { it.name })
                } catch (e: Exception) {
                    Log.e("OrgFileRepositoryImpl", "Error getting directories for search", e)
                    searchResults
                }
            }
        }

        // For listing all files (no search query), always use FileDataSource
        Log.d("OrgFileRepositoryImpl", "Getting all files - using FileDataSource for file listing")
        return favoriteRepository.getFavoriteUrisFlow().combine(flowOf(Unit)) { favoriteUris, _ ->
            try {
                val fileSourceFiles = fileDataSource.getOrgFiles(null, null)
                Log.d("OrgFileRepositoryImpl", "FileDataSource returned ${fileSourceFiles.size} files")
                fileSourceFiles.map { file ->
                    file.copy(isFavorite = favoriteUris.contains(file.uri.toString()))
                }
            } catch (e: Exception) {
                Log.e("OrgFileRepositoryImpl", "Error getting files from FileDataSource", e)
                emptyList()
            }
        }
    }

    override suspend fun readOrgFile(uri: Uri): Result<OrgDocument> {
        return try {
            val path = uri.path ?: return Result.failure(IllegalArgumentException("Invalid URI path"))
            val content = fileDataSource.readFile(uri)
            val (preamble, nodes) = orgParserWrapper.parseContent(content)
            val file = File(path)
            
            val document = OrgDocument(
                uri = uri,
                fileName = file.name,
                content = content,
                lastModified = file.lastModified(),
                nodes = nodes,
                preamble = preamble
            )
            
            Result.success(document)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun writeOrgFile(document: OrgDocument): Result<Unit> {
        return try {
            val content = if (document.nodes.isNotEmpty()) {
                orgParserWrapper.writeContent(document.preamble, document.nodes)
            } else {
                document.content
            }
            
            fileDataSource.writeFile(document.uri, content)
            // After writing, we should update the index
            val fileInfo = OrgFileInfo(
                uri = document.uri,
                name = document.fileName,
                lastModified = System.currentTimeMillis(),
                size = content.length.toLong(),
                isDirectory = false
            )
            fileDao.insertFileMetadata(fileInfo.toFileMetadataEntity())
            fileDao.insertFileContent(fileInfo.toFileContentFtsEntity(content))

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createOrgFile(name: String, content: String): Result<Uri> {
        return try {
            val uri = fileDataSource.createFile(name, content)
            // After creating, we should update the index
            val fileName = if (name.endsWith(".org")) name else "$name.org"
            val fileInfo = OrgFileInfo(
                uri = uri,
                name = fileName,
                lastModified = System.currentTimeMillis(),
                size = content.length.toLong(),
                isDirectory = false
            )
            fileDao.insertFileMetadata(fileInfo.toFileMetadataEntity())
            fileDao.insertFileContent(fileInfo.toFileContentFtsEntity(content))
            Result.success(uri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteOrgFile(uri: Uri): Result<Unit> {
        return try {
            fileDataSource.deleteFile(uri)
            // After deleting, we should update the index
            fileDao.deleteFileMetadataByPaths(listOf(uri.toString()))
            fileDao.deleteFileContentByPaths(listOf(uri.toString()))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun hasDocumentAccess(): Boolean {
        return fileDataSource.hasDocumentAccess()
    }

    override suspend fun requestDocumentAccess(): Boolean {
        return fileDataSource.requestDocumentAccess()
    }

    override suspend fun appendToCaptureFile(content: String): Result<Unit> {
        return try {
            fileDataSource.appendToCaptureFile(content)
            // After appending, we should update the index for capture file
            // This is a bit tricky as we don't have the URI here. 
            // We'll need to get it from the file data source.
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getCaptureFileSize(): Long {
        return try {
            fileDataSource.getCaptureFileSize()
        } catch (e: Exception) {
            throw e
        }
    }

    private fun OrgFileInfo.toFileMetadataEntity() = com.orgutil.data.database.entity.FileMetadataEntity(
        path = this.uri.toString(),
        fileName = this.name,
        lastModified = this.lastModified,
        size = this.size
    )

    private fun OrgFileInfo.toFileContentFtsEntity(content: String) = com.orgutil.data.database.entity.FileContentFtsEntity(
        path = this.uri.toString(),
        content = content
    )

    /**
     * Prepares FTS query to handle Chinese characters and implement fuzzy matching
     */
    private fun prepareFtsQuery(query: String): String {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            return trimmedQuery
        }

        // Check if query contains Chinese characters
        val containsChinese = trimmedQuery.any { it.toString().matches("[\\u4e00-\\u9fa5]".toRegex()) }

        if (containsChinese) {
            // For Chinese characters, use phrase search to ensure proper matching
            // Also split by spaces to handle mixed Chinese/English queries
            val parts = trimmedQuery.split("\\s+".toRegex()).filter { it.isNotBlank() }

            return if (parts.size == 1) {
                // Single phrase, use quotes for exact matching of Chinese characters
                val escapedQuery = parts[0].replace("\"", "\"\"")
                "\"$escapedQuery\""
            } else {
                // Multiple parts, each part should be found
                val ftsWords = parts.map { part ->
                    val escapedPart = part.replace("\"", "\"\"")
                    if (part.any { it.toString().matches("[\\u4e00-\\u9fa5]".toRegex()) }) {
                        // Chinese part - use phrase search
                        "\"$escapedPart\""
                    } else {
                        // English part - use prefix matching
                        "$escapedPart*"
                    }
                }
                ftsWords.joinToString(" AND ")
            }
        } else {
            // For non-Chinese text, use prefix matching
            val words = trimmedQuery.split("\\s+".toRegex()).filter { it.isNotBlank() }

            return if (words.size == 1) {
                // For single word, use prefix matching with *
                val word = words[0]
                // Escape special FTS characters except *
                val escapedWord = word.replace("\"", "\"\"")
                "$escapedWord*"
            } else {
                // For multiple words, each word should be found (AND operation with prefix matching)
                val ftsWords = words.map { word ->
                    val escapedWord = word.replace("\"", "\"\"")
                    "$escapedWord*"
                }
                ftsWords.joinToString(" AND ")
            }
        }
    }
}
