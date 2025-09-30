package com.orgutil.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.orgutil.data.database.dao.FileDao
import com.orgutil.data.database.entity.FileContentFtsEntity
import com.orgutil.data.database.entity.FileMetadataEntity
import com.orgutil.data.datasource.FileDataSource
import com.orgutil.domain.model.OrgFileInfo
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltWorker
class FileIndexerWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val fileDao: FileDao,
    private val fileDataSource: FileDataSource
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d("FileIndexerWorker", "Starting file indexing work...")
        try {
            indexFiles()
            Log.d("FileIndexerWorker", "File indexing completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e("FileIndexerWorker", "File indexing failed", e)
            e.printStackTrace()
            Result.failure()
        }
    }

    private suspend fun indexFiles() {
        Log.d("FileIndexerWorker", "Getting all org files from FileDataSource...")
        // Get all .org files from the specified directory
        val allOrgFiles = fileDataSource.getAllOrgFiles()
        Log.d("FileIndexerWorker", "Found ${allOrgFiles.size} org files from FileDataSource")

        val allOrgFilePaths = allOrgFiles.map { it.uri.toString() }

        // Get all file paths currently in the database
        Log.d("FileIndexerWorker", "Getting indexed file paths from database...")
        val indexedFilePaths = fileDao.getAllFilePaths()
        Log.d("FileIndexerWorker", "Found ${indexedFilePaths.size} files already indexed in database")

        // Identify new, updated, and deleted files
        val newFiles = allOrgFiles.filter { it.uri.toString() !in indexedFilePaths }
        val deletedFilePaths = indexedFilePaths.filter { it !in allOrgFilePaths }
        Log.d("FileIndexerWorker", "New files: ${newFiles.size}, Deleted files: ${deletedFilePaths.size}")

        // Get metadata for existing files to check for modifications
        val existingFilesMetadata = fileDao.getFileMetadataByPaths(indexedFilePaths)
        val updatedFiles = existingFilesMetadata.mapNotNull { metadata ->
            val file = allOrgFiles.find { it.uri.toString() == metadata.path }
            if (file != null && (file.lastModified != metadata.lastModified || file.size != metadata.size)) {
                file
            } else {
                null
            }
        }
        Log.d("FileIndexerWorker", "Updated files: ${updatedFiles.size}")

        // Process new files
        if (newFiles.isNotEmpty()) {
            Log.d("FileIndexerWorker", "Indexing ${newFiles.size} new files...")
            val newMetadata = newFiles.map { it.toFileMetadataEntity() }
            val newContent = newFiles.mapNotNull { it.toFileContentFtsEntity() }
            fileDao.insertAllFileMetadata(newMetadata)
            fileDao.insertAllFileContent(newContent)
            Log.d("FileIndexerWorker", "Inserted ${newMetadata.size} metadata and ${newContent.size} content entries")
        }

        // Process updated files
        if (updatedFiles.isNotEmpty()) {
            Log.d("FileIndexerWorker", "Updating ${updatedFiles.size} modified files...")
            val updatedMetadata = updatedFiles.map { it.toFileMetadataEntity() }
            val updatedContent = updatedFiles.mapNotNull { it.toFileContentFtsEntity() }
            fileDao.insertAllFileMetadata(updatedMetadata)
            fileDao.insertAllFileContent(updatedContent)
            Log.d("FileIndexerWorker", "Updated ${updatedMetadata.size} metadata and ${updatedContent.size} content entries")
        }

        // Process deleted files
        if (deletedFilePaths.isNotEmpty()) {
            Log.d("FileIndexerWorker", "Removing ${deletedFilePaths.size} deleted files from database...")
            fileDao.deleteFileMetadataByPaths(deletedFilePaths)
            fileDao.deleteFileContentByPaths(deletedFilePaths)
            Log.d("FileIndexerWorker", "Removed ${deletedFilePaths.size} files from database")
        }

        if (newFiles.isEmpty() && updatedFiles.isEmpty() && deletedFilePaths.isEmpty()) {
            Log.d("FileIndexerWorker", "No changes detected - database is up to date")
        }
    }

    private fun com.orgutil.domain.model.OrgFileInfo.toFileMetadataEntity() = FileMetadataEntity(
        path = this.uri.toString(),
        fileName = this.name,
        lastModified = this.lastModified,
        size = this.size
    )

    private suspend fun com.orgutil.domain.model.OrgFileInfo.toFileContentFtsEntity(): FileContentFtsEntity? {
        return try {
            val content = fileDataSource.readFile(this.uri)
            FileContentFtsEntity(
                path = this.uri.toString(),
                content = content
            )
        } catch (e: Exception) {
            // Log the error but don't fail the entire indexing process
            Log.e("FileIndexerWorker", "Failed to read file content for ${this.uri}", e)
            null
        }
    }
}