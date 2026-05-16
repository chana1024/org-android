package com.orgutil.data.datasource

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.orgutil.domain.model.OrgFileInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileDataSourceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documentTreeStore: DocumentTreeStore,
    private val orgInboxStore: OrgInboxStore,
    private val orgFileScanner: OrgFileScanner
) : FileDataSource {

    override suspend fun getOrgFiles(uri: Uri?, query: String?): List<OrgFileInfo> {
        return orgFileScanner.getOrgFiles(uri, query)
    }

    override suspend fun readFile(uri: Uri): String = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.readText()
                }
            } ?: throw IOException("Could not open file for reading")
        } catch (e: Exception) {
            throw IOException("Failed to read file: ${e.message}", e)
        }
    }

    override suspend fun writeFile(uri: Uri, content: String): Unit = withContext(Dispatchers.IO) {
        try {
            val outputStream = context.contentResolver.openOutputStream(uri, "wt")
            if (outputStream == null) {
                throw IOException("Could not open file for writing - no permission or invalid URI")
            }

            outputStream.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { writer ->
                    writer.write(content)
                    writer.flush()
                }
                os.flush()
            }
        } catch (e: Exception) {
            throw IOException("Failed to write file: ${e.message}", e)
        }
    }

    override suspend fun createFile(name: String, content: String): Uri = withContext(Dispatchers.IO) {
        val treeUri = documentTreeStore.getStoredTreeUri() ?: throw IOException("No document tree access")
        try {
            val documentFile = DocumentFile.fromTreeUri(context, treeUri)
            if (documentFile?.exists() != true || !documentFile.isDirectory) {
                throw IOException("Document directory not accessible")
            }

            val fileName = if (name.endsWith(".org")) name else "$name.org"
            val newFile = documentFile.createFile("text/org", fileName)
                ?: throw IOException("Could not create file")

            writeFile(newFile.uri, content)
            newFile.uri
        } catch (e: Exception) {
            throw IOException("Failed to create file: ${e.message}", e)
        }
    }

    override suspend fun deleteFile(uri: Uri) = withContext(Dispatchers.IO) {
        try {
            val documentFile = DocumentFile.fromSingleUri(context, uri)
            if (documentFile?.exists() == true) {
                documentFile.delete()
            }
        } catch (e: Exception) {
            throw IOException("Failed to delete file: ${e.message}", e)
        }
    }

    override suspend fun hasDocumentAccess(): Boolean {
        return documentTreeStore.hasDocumentAccess()
    }

    override suspend fun requestDocumentAccess(): Boolean {
        return hasDocumentAccess()
    }

    override fun storeTreeUri(uri: Uri) {
        documentTreeStore.storeTreeUri(uri)
    }

    override fun getStoredTreeUri(): Uri? {
        return documentTreeStore.getStoredTreeUri()
    }

    override suspend fun appendToCaptureFile(content: String) {
        orgInboxStore.append(content)
    }

    override suspend fun getCaptureFileSize(): Long {
        return orgInboxStore.size()
    }

    override suspend fun getCaptureFileUri(): Uri? {
        return orgInboxStore.getInboxUri()
    }

    override suspend fun getAllOrgFiles(): List<OrgFileInfo> {
        return orgFileScanner.getAllOrgFiles()
    }

    override suspend fun getAllOrgFilesUnder(uri: Uri): List<OrgFileInfo> {
        return orgFileScanner.getAllOrgFilesUnder(uri)
    }

    override suspend fun getFileInfo(uri: Uri): OrgFileInfo? {
        return orgFileScanner.getFileInfo(uri)
    }
}
