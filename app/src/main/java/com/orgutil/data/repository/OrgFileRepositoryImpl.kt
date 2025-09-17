package com.orgutil.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.orgutil.data.datasource.FileDataSource
import com.orgutil.data.mapper.OrgParserWrapper
import com.orgutil.domain.model.OrgDocument
import com.orgutil.domain.model.OrgFileInfo
import com.orgutil.domain.repository.OrgFileRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OrgFileRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileDataSource: FileDataSource,
    private val orgParserWrapper: OrgParserWrapper
) : OrgFileRepository {

    override suspend fun getOrgFiles(): Flow<List<OrgFileInfo>> = flow {
        try {
            val files = fileDataSource.getOrgFiles()
            emit(files)
        } catch (e: Exception) {
            emit(emptyList())
        }
    }

    override suspend fun readOrgFile(uri: Uri): Result<OrgDocument> {
        return try {
            val content = fileDataSource.readFile(uri)
            val (preamble, nodes) = orgParserWrapper.parseContent(content)
            
            val fileName = DocumentFile.fromSingleUri(context, uri)?.name ?: "Unknown"
            val lastModified = DocumentFile.fromSingleUri(context, uri)?.lastModified() ?: 0L
            
            val document = OrgDocument(
                uri = uri,
                fileName = fileName,
                content = content,
                lastModified = lastModified,
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
            // Fix for Issue 2: Always use the document.content if it's been edited
            // Only regenerate from nodes if document.content appears to be outdated
            val content = if (document.nodes.isNotEmpty() && shouldRegenerateFromNodes(document)) {
                orgParserWrapper.writeContent(document.preamble, document.nodes)
            } else {
                document.content
            }
            
            fileDataSource.writeFile(document.uri, content)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun shouldRegenerateFromNodes(document: OrgDocument): Boolean {
        // Only regenerate from nodes if the content appears to be the original parsed content
        // and hasn't been manually edited. For now, we'll be conservative and prefer 
        // document.content to preserve edits
        return false
    }

    override suspend fun createOrgFile(name: String, content: String): Result<Uri> {
        return try {
            val uri = fileDataSource.createFile(name, content)
            Result.success(uri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteOrgFile(uri: Uri): Result<Unit> {
        return try {
            fileDataSource.deleteFile(uri)
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
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}