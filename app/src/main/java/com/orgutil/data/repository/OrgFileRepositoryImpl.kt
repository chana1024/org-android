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
import java.io.IOException

@Singleton
class OrgFileRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileDao: FileDao,
    private val orgParserWrapper: OrgParserWrapper,
    private val favoriteRepository: FavoriteRepository,
    private val fileDataSource: FileDataSource // Keep for file operations
) : OrgFileRepository {

    override suspend fun getOrgFiles(uri: Uri?, query: String?, useDatabase: Boolean): Flow<List<OrgFileInfo>> {
        Log.d("OrgFileRepositoryImpl", "getOrgFiles called with uri: $uri, query: '$query', useDatabase: $useDatabase")

        // If a specific URI is provided, use FileDataSource directly for directory browsing
        if (uri != null) {
            Log.d("OrgFileRepositoryImpl", "Using FileDataSource for specific URI")
            return favoriteRepository.getFavoriteUrisFlow().combine(flowOf(Unit)) { favoriteUris, _ ->
                try {
                    val files = fileDataSource.getOrgFiles(uri, query)
                    Log.d("OrgFileRepositoryImpl", "FileDataSource returned ${files.size} files for URI")
                    files.map { file ->
                        file.copy(isFavorite = favoriteUris.contains(file.uri.toString()))
                    }
                } catch (e: Exception) {
                    Log.e("OrgFileRepositoryImpl", "Error getting files from FileDataSource for URI", e)
                    emptyList()
                }
            }
        }

        // For search queries, only use database if useDatabase flag is true
        if (!query.isNullOrBlank() && useDatabase) {
            Log.d("OrgFileRepositoryImpl", "Using database search for query: '$query'")
            // For FTS queries, we need to handle Chinese characters properly
            val ftsQuery = prepareFtsQuery(query)

            // Execute both queries in parallel and merge results
            return favoriteRepository.getFavoriteUrisFlow().combine(flowOf(Unit)) { favoriteUris, _ ->
                try {
                    // Search by filename and content separately for better performance
                    val fileNameResults = fileDao.searchFilesByName(query)
                    val contentResults = fileDao.searchFilesByContent(ftsQuery)

                    // Merge and deduplicate results
                    val allResults = (fileNameResults + contentResults)
                        .distinctBy { it.path }
                        .sortedBy { it.fileName }

                    Log.d("OrgFileRepositoryImpl", "Found ${fileNameResults.size} by name, ${contentResults.size} by content, ${allResults.size} total")

                    allResults.map { metadata ->
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
                } catch (e: Exception) {
                    Log.e("OrgFileRepositoryImpl", "Error searching files", e)
                    emptyList()
                }
            }
        }

        // For all other cases (no search query, or search disabled), use FileDataSource
        Log.d("OrgFileRepositoryImpl", "Getting files - using FileDataSource for file listing")
        return favoriteRepository.getFavoriteUrisFlow().combine(flowOf(Unit)) { favoriteUris, _ ->
            try {
                val fileSourceFiles = fileDataSource.getOrgFiles(null, query)
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

    override suspend fun getAllOrgFiles(): List<OrgFileInfo> {
        return fileDataSource.getAllOrgFiles()
    }

    override suspend fun getFileInfo(uri: Uri): OrgFileInfo? {
        return fileDataSource.getFileInfo(uri)
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
        Log.d("OrgFileRepositoryImpl", "writeOrgFile called - URI: ${document.uri}, fileName: ${document.fileName}")
        return try {
            // IMPORTANT: Always use document.content directly, not regenerated from nodes
            // The document.content field contains the edited content from the editor
            // The nodes field contains the parsed structure from when the file was loaded
            // If we regenerate from nodes, we lose any edits made in the editor!
            val content = document.content

            Log.d("OrgFileRepositoryImpl", "Using document.content directly (${content.length} chars)")
            Log.d("OrgFileRepositoryImpl", "Content preview: ${content.take(100)}")

            // Write the file
            fileDataSource.writeFile(document.uri, content)
            Log.d("OrgFileRepositoryImpl", "fileDataSource.writeFile completed")

            // VERIFICATION: Read back the content to verify it was actually written
            try {
                val readBackContent = fileDataSource.readFile(document.uri)
                Log.d("OrgFileRepositoryImpl", "Read-back verification - Original: ${content.length} chars, Read-back: ${readBackContent.length} chars")

                if (readBackContent != content) {
                    Log.e("OrgFileRepositoryImpl", "VERIFICATION FAILED! Content mismatch after write")
                    Log.e("OrgFileRepositoryImpl", "Expected content (first 200): ${content.take(200)}")
                    Log.e("OrgFileRepositoryImpl", "Actual content (first 200): ${readBackContent.take(200)}")
                    return Result.failure(IOException("File verification failed: content mismatch after write"))
                }
                Log.d("OrgFileRepositoryImpl", "Read-back verification PASSED - content matches!")
            } catch (e: Exception) {
                Log.e("OrgFileRepositoryImpl", "Read-back verification failed with exception", e)
                return Result.failure(IOException("File verification failed: ${e.message}", e))
            }

            // After writing, we should update the index
            val fileInfo = OrgFileInfo(
                uri = document.uri,
                name = document.fileName,
                lastModified = System.currentTimeMillis(),
                size = content.length.toLong(),
                isDirectory = false
            )
            fileDao.insertFileMetadata(fileInfo.toFileMetadataEntity())
            Log.d("OrgFileRepositoryImpl", "File metadata inserted")

            fileDao.insertFileContent(fileInfo.toFileContentFtsEntity(content))
            Log.d("OrgFileRepositoryImpl", "File content inserted to FTS")

            Log.d("OrgFileRepositoryImpl", "writeOrgFile completed successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("OrgFileRepositoryImpl", "writeOrgFile failed", e)
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
