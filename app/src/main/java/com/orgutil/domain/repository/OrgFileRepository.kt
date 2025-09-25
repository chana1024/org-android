package com.orgutil.domain.repository

import android.net.Uri
import com.orgutil.domain.model.OrgDocument
import com.orgutil.domain.model.OrgFileInfo
import kotlinx.coroutines.flow.Flow

interface OrgFileRepository {
    suspend fun getOrgFiles(uri: Uri? = null, query: String? = null): Flow<List<OrgFileInfo>>
    suspend fun readOrgFile(uri: Uri): Result<OrgDocument>
    suspend fun writeOrgFile(document: OrgDocument): Result<Unit>
    suspend fun createOrgFile(name: String, content: String): Result<Uri>
    suspend fun deleteOrgFile(uri: Uri): Result<Unit>
    suspend fun hasDocumentAccess(): Boolean
    suspend fun requestDocumentAccess(): Boolean
    suspend fun appendToCaptureFile(content: String): Result<Unit>
    suspend fun getCaptureFileSize(): Long
}