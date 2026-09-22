package com.orgutil.data.repository

import com.orgutil.data.database.dao.FileDao
import com.orgutil.data.database.entity.FileContentEntity
import com.orgutil.data.database.entity.FileContentFtsEntity
import com.orgutil.data.database.entity.FileMetadataEntity
import com.orgutil.data.datasource.FileDataSource
import com.orgutil.domain.indexing.FileIndexRunner
import com.orgutil.domain.model.OrgFileInfo
import com.orgutil.domain.search.CjkTextEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rebuilds the search index incrementally.
 *
 * Consistency rules:
 * - metadata / FTS / plain content for one file are always written together
 *   in a single transaction ([FileDao.replaceIndexedFiles]);
 * - a file whose body cannot be read gets NO metadata row (new files) or
 *   keeps its stale metadata (changed files), so the next pass retries it;
 * - metadata rows whose FTS content is missing (failed past run, or the
 *   3->4 migration which wiped the FTS table) are repaired by re-reading
 *   and re-indexing the body (self-healing).
 */
@Singleton
class OrgFileIndexUpdater @Inject constructor(
    private val fileDao: FileDao,
    private val fileDataSource: FileDataSource
) : FileIndexRunner {

    override suspend fun run(): FileIndexUpdateReport {
        val allOrgFiles = fileDataSource.getAllOrgFiles()
        val allOrgFilePaths = allOrgFiles.map { it.uri.toString() }.toSet()
        val fileByPath = allOrgFiles.associateBy { it.uri.toString() }

        val indexedFilePaths = fileDao.getAllFilePaths()
        val indexedPathSet = indexedFilePaths.toSet()
        val deletedFilePaths = indexedFilePaths.filter { it !in allOrgFilePaths }

        // O(N) diff instead of the previous per-file list lookup.
        val existingMetadataByPath =
            if (indexedFilePaths.isEmpty()) {
                emptyMap()
            } else {
                chunked(indexedFilePaths)
                    .flatMap { fileDao.getFileMetadataByPaths(it) }
                    .associateBy { it.path }
            }

        val newFiles = allOrgFiles.filter { it.uri.toString() !in indexedPathSet }
        val changedFiles = allOrgFiles.filter { file ->
            val metadata = existingMetadataByPath[file.uri.toString()] ?: return@filter false
            file.lastModified != metadata.lastModified || file.size != metadata.size
        }

        // Self-heal: metadata exists but the FTS row is gone (failed content
        // read in a previous run, or the migration wiped the FTS table).
        val newPathSet = newFiles.mapTo(mutableSetOf()) { it.uri.toString() }
        val pathsToHeal = fileDao.getMetadataPathsWithoutFtsContent()
            .filter { it !in newPathSet }
            .mapNotNull { path -> fileByPath[path] }

        val contentFailures = mutableListOf<FileIndexContentFailure>()
        var repairedCount = 0

        if (deletedFilePaths.isNotEmpty()) {
            chunked(deletedFilePaths).forEach { fileDao.deleteIndexedFiles(it) }
        }

        if (newFiles.isNotEmpty()) {
            indexBatch(newFiles, contentFailures)
        }

        if (changedFiles.isNotEmpty()) {
            indexBatch(changedFiles, contentFailures)
        }

        if (pathsToHeal.isNotEmpty()) {
            val healed = indexBatch(pathsToHeal, contentFailures)
            repairedCount += healed
        }

        return FileIndexUpdateReport(
            scannedFileCount = allOrgFiles.size,
            insertedCount = newFiles.size,
            updatedCount = changedFiles.size,
            deletedCount = deletedFilePaths.size,
            repairedCount = repairedCount,
            contentFailures = contentFailures
        )
    }

    /**
     * Reads each file's body and writes metadata + FTS + plain content in one
     * transaction per write batch. Files whose read fails are recorded in
     * [contentFailures] and left untouched in the DB. Returns the number of
     * files actually written.
     */
    private suspend fun indexBatch(
        files: List<OrgFileInfo>,
        contentFailures: MutableList<FileIndexContentFailure>
    ): Int {
        var written = 0
        chunked(files).forEach { batch ->
            val metadata = mutableListOf<FileMetadataEntity>()
            val ftsContent = mutableListOf<FileContentFtsEntity>()
            val plainContent = mutableListOf<FileContentEntity>()

            for (file in batch) {
                runCatching { fileDataSource.readFile(file.uri) }
                    .onSuccess { content ->
                        metadata += file.toFileMetadataEntity()
                        ftsContent += FileContentFtsEntity(
                            path = file.uri.toString(),
                            content = CjkTextEncoder.encodeForIndex(content)
                        )
                        plainContent += FileContentEntity(
                            path = file.uri.toString(),
                            content = content
                        )
                    }
                    .onFailure {
                        contentFailures += FileIndexContentFailure(
                            path = file.uri.toString(),
                            reason = it.message ?: "Unknown content read failure"
                        )
                    }
            }

            if (metadata.isNotEmpty()) {
                fileDao.replaceIndexedFiles(metadata, ftsContent, plainContent)
                written += metadata.size
            }
        }
        return written
    }

    private fun <T> chunked(items: List<T>, chunkSize: Int = CHUNK_SIZE): List<List<T>> =
        if (items.size <= chunkSize) listOf(items) else items.chunked(chunkSize)

    private fun OrgFileInfo.toFileMetadataEntity() = FileMetadataEntity(
        path = this.uri.toString(),
        fileName = this.name,
        lastModified = this.lastModified,
        size = this.size
    )

    private companion object {
        // SQLite's default host-parameter limit is 999; keep IN() lists,
        // which expand to one parameter per element, safely below it.
        const val CHUNK_SIZE = 900
    }
}

data class FileIndexUpdateReport(
    val scannedFileCount: Int,
    val insertedCount: Int,
    val updatedCount: Int,
    val deletedCount: Int,
    val repairedCount: Int = 0,
    val contentFailures: List<FileIndexContentFailure> = emptyList()
) {
    val skippedContentCount: Int
        get() = contentFailures.size
}

data class FileIndexContentFailure(
    val path: String,
    val reason: String
)
