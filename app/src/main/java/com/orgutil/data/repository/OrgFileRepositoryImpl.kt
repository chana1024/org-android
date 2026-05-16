package com.orgutil.data.repository

import android.net.Uri
import android.util.Log
import com.orgutil.data.database.dao.FileDao
import com.orgutil.data.datasource.FileDataSource
import com.orgutil.data.mapper.OrgParserWrapper
import com.orgutil.domain.model.OrgDocument
import com.orgutil.domain.model.OrgFileInfo
import com.orgutil.domain.repository.OrgFileRepository
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OrgFileRepositoryImpl @Inject constructor(
    private val fileDao: FileDao,
    private val orgParserWrapper: OrgParserWrapper,
    private val fileDataSource: FileDataSource,
    private val orgFileQueryService: OrgFileQueryService
) : OrgFileRepository {

    override suspend fun getOrgFiles(uri: Uri?, query: String?, useDatabase: Boolean): Flow<List<OrgFileInfo>> {
        return orgFileQueryService.observeOrgFiles(uri, query, useDatabase)
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
        safeLogD(TAG, "writeOrgFile called - URI: ${document.uri}, fileName: ${document.fileName}")
        return try {
            val content = document.content
            safeLogD(TAG, "Using document.content directly (${content.length} chars)")
            safeLogD(TAG, "Content preview: ${content.take(100)}")

            fileDataSource.writeFile(document.uri, content)
            safeLogD(TAG, "fileDataSource.writeFile completed")
            verifyWrittenContent(document.uri, content)
            syncIndexedFile(document.uri, fallbackName = document.fileName)
        } catch (e: Exception) {
            safeLogE(TAG, "writeOrgFile failed", e)
            Result.failure(e)
        }
    }

    override suspend fun createOrgFile(name: String, content: String): Result<Uri> {
        return try {
            val uri = fileDataSource.createFile(name, content)
            val fileName = if (name.endsWith(".org")) name else "$name.org"
            syncIndexedFile(uri, fallbackName = fileName)
                .map { uri }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteOrgFile(uri: Uri): Result<Unit> {
        return try {
            fileDataSource.deleteFile(uri)
            deleteIndexedFile(uri)
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
            val captureUri = fileDataSource.getCaptureFileUri()
                ?: return Result.failure(IllegalStateException("Capture file URI unavailable after append"))
            syncIndexedFile(captureUri, fallbackName = "inbox.org")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getCaptureFileSize(): Long {
        return fileDataSource.getCaptureFileSize()
    }

    private suspend fun verifyWrittenContent(uri: Uri, expectedContent: String) {
        try {
            val readBackContent = fileDataSource.readFile(uri)
            safeLogD(
                TAG,
                "Read-back verification - Original: ${expectedContent.length} chars, Read-back: ${readBackContent.length} chars"
            )
            if (readBackContent != expectedContent) {
                safeLogE(TAG, "VERIFICATION FAILED! Content mismatch after write")
                throw IOException("File verification failed: content mismatch after write")
            }
            safeLogD(TAG, "Read-back verification PASSED - content matches!")
        } catch (e: Exception) {
            safeLogE(TAG, "Read-back verification failed with exception", e)
            if (e is IOException) throw e
            throw IOException("File verification failed: ${e.message}", e)
        }
    }

    private suspend fun syncIndexedFile(uri: Uri, fallbackName: String): Result<Unit> {
        return runCatching {
            val fileContent = fileDataSource.readFile(uri)
            val fileInfo = fileDataSource.getFileInfo(uri)
            val indexedInfo = (fileInfo ?: OrgFileInfo(
                uri = uri,
                name = fallbackName,
                lastModified = System.currentTimeMillis(),
                size = fileContent.length.toLong(),
                isDirectory = false
            )).copy(
                name = fileInfo?.name ?: fallbackName,
                lastModified = System.currentTimeMillis(),
                size = fileContent.length.toLong(),
                isDirectory = false
            )
            fileDao.insertFileMetadata(indexedInfo.toFileMetadataEntity())
            fileDao.insertFileContent(indexedInfo.toFileContentFtsEntity(fileContent))
        }
    }

    private suspend fun deleteIndexedFile(uri: Uri) {
        val paths = listOf(uri.toString())
        fileDao.deleteFileMetadataByPaths(paths)
        fileDao.deleteFileContentByPaths(paths)
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

    private companion object {
        const val TAG = "OrgFileRepositoryImpl"
    }
}

private fun safeLogD(tag: String, message: String) {
    runCatching { Log.d(tag, message) }
}

private fun safeLogE(tag: String, message: String, throwable: Throwable? = null) {
    runCatching {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
}
