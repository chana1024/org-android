package com.orgutil.data.datasource

import android.net.Uri
import com.orgutil.domain.model.OrgFileInfo

interface FileDataSource {
    suspend fun getOrgFiles(uri: Uri? = null, query: String? = null): List<OrgFileInfo>
    suspend fun readFile(uri: Uri): String
    suspend fun writeFile(uri: Uri, content: String)
    suspend fun createFile(name: String, content: String): Uri
    suspend fun deleteFile(uri: Uri)
    suspend fun hasDocumentAccess(): Boolean
    suspend fun requestDocumentAccess(): Boolean
    suspend fun appendToCaptureFile(content: String)
    suspend fun getCaptureFileSize(): Long
}