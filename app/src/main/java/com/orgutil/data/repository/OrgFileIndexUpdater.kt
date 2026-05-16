package com.orgutil.data.repository

import com.orgutil.data.database.dao.FileDao
import com.orgutil.data.database.entity.FileContentFtsEntity
import com.orgutil.data.database.entity.FileMetadataEntity
import com.orgutil.data.datasource.FileDataSource
import com.orgutil.domain.indexing.FileIndexRunner
import com.orgutil.domain.model.OrgFileInfo
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OrgFileIndexUpdater @Inject constructor(
    private val fileDao: FileDao,
    private val fileDataSource: FileDataSource
) : FileIndexRunner {
    override suspend fun run(): FileIndexUpdateReport {
        val allOrgFiles = fileDataSource.getAllOrgFiles()
        val allOrgFilePaths = allOrgFiles.map { it.uri.toString() }

        val indexedFilePaths = fileDao.getAllFilePaths()
        val newFiles = allOrgFiles.filter { it.uri.toString() !in indexedFilePaths }
        val deletedFilePaths = indexedFilePaths.filter { it !in allOrgFilePaths }

        val existingFilesMetadata = fileDao.getFileMetadataByPaths(indexedFilePaths)
        val updatedFiles = existingFilesMetadata.mapNotNull { metadata ->
            allOrgFiles.find { it.uri.toString() == metadata.path }
                ?.takeIf { file -> file.lastModified != metadata.lastModified || file.size != metadata.size }
        }

        val contentFailures = mutableListOf<FileIndexContentFailure>()

        if (newFiles.isNotEmpty()) {
            val newMetadata = newFiles.map { it.toFileMetadataEntity() }
            val newContent = newFiles.mapNotNull { file ->
                file.toFileContentResult().fold(
                    onSuccess = { it },
                    onFailure = {
                        contentFailures += FileIndexContentFailure(
                            path = file.uri.toString(),
                            reason = it.message ?: "Unknown content read failure"
                        )
                        null
                    }
                )
            }
            fileDao.insertAllFileMetadata(newMetadata)
            fileDao.insertAllFileContent(newContent)
        }

        if (updatedFiles.isNotEmpty()) {
            val updatedMetadata = updatedFiles.map { it.toFileMetadataEntity() }
            val updatedContent = updatedFiles.mapNotNull { file ->
                file.toFileContentResult().fold(
                    onSuccess = { it },
                    onFailure = {
                        contentFailures += FileIndexContentFailure(
                            path = file.uri.toString(),
                            reason = it.message ?: "Unknown content read failure"
                        )
                        null
                    }
                )
            }
            fileDao.insertAllFileMetadata(updatedMetadata)
            fileDao.insertAllFileContent(updatedContent)
        }

        if (deletedFilePaths.isNotEmpty()) {
            fileDao.deleteFileMetadataByPaths(deletedFilePaths)
            fileDao.deleteFileContentByPaths(deletedFilePaths)
        }

        return FileIndexUpdateReport(
            scannedFileCount = allOrgFiles.size,
            insertedCount = newFiles.size,
            updatedCount = updatedFiles.size,
            deletedCount = deletedFilePaths.size,
            contentFailures = contentFailures
        )
    }

    private fun OrgFileInfo.toFileMetadataEntity() = FileMetadataEntity(
        path = this.uri.toString(),
        fileName = this.name,
        lastModified = this.lastModified,
        size = this.size
    )

    private suspend fun OrgFileInfo.toFileContentResult(): Result<FileContentFtsEntity> {
        return runCatching {
            FileContentFtsEntity(
                path = this.uri.toString(),
                content = fileDataSource.readFile(this.uri)
            )
        }
    }
}

data class FileIndexUpdateReport(
    val scannedFileCount: Int,
    val insertedCount: Int,
    val updatedCount: Int,
    val deletedCount: Int,
    val contentFailures: List<FileIndexContentFailure> = emptyList()
) {
    val skippedContentCount: Int
        get() = contentFailures.size
}

data class FileIndexContentFailure(
    val path: String,
    val reason: String
)
