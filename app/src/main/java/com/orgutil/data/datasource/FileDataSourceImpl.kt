package com.orgutil.data.datasource

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.orgutil.domain.model.OrgFileInfo
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
    private val context: Context
) : FileDataSource {

    private val prefs: SharedPreferences = context.getSharedPreferences("org_util_prefs", Context.MODE_PRIVATE)
    private val DOCUMENT_TREE_URI_KEY = "document_tree_uri"

    override suspend fun getOrgFiles(): List<OrgFileInfo> = withContext(Dispatchers.IO) {
        val treeUri = getStoredTreeUri() ?: return@withContext emptyList()
        
        try {
            val documentFile = DocumentFile.fromTreeUri(context, treeUri)
            if (documentFile?.exists() != true || !documentFile.isDirectory) {
                return@withContext emptyList()
            }

            val orgFiles = mutableListOf<OrgFileInfo>()
            documentFile.listFiles().forEach { file ->
                if (file.isFile && file.name?.endsWith(".org") == true) {
                    orgFiles.add(
                        OrgFileInfo(
                            uri = file.uri,
                            name = file.name ?: "Unknown",
                            lastModified = file.lastModified(),
                            size = file.length()
                        )
                    )
                }
            }
            orgFiles.sortedByDescending { it.lastModified }
        } catch (e: Exception) {
            emptyList()
        }
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

    override suspend fun writeFile(uri: Uri, content: String) = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writer.write(content)
                }
            } ?: throw IOException("Could not open file for writing")
        } catch (e: Exception) {
            throw IOException("Failed to write file: ${e.message}", e)
        }
    }

    override suspend fun createFile(name: String, content: String): Uri = withContext(Dispatchers.IO) {
        val treeUri = getStoredTreeUri() ?: throw IOException("No document tree access")
        
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
        val treeUri = getStoredTreeUri() ?: return false
        
        return try {
            val documentFile = DocumentFile.fromTreeUri(context, treeUri)
            documentFile?.exists() == true && documentFile.isDirectory
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun requestDocumentAccess(): Boolean {
        // This will be handled by the UI layer using ActivityResultLauncher
        // This method just checks if we can use the stored URI
        return hasDocumentAccess()
    }

    fun storeTreeUri(uri: Uri) {
        prefs.edit().putString(DOCUMENT_TREE_URI_KEY, uri.toString()).apply()
        
        // Take persistable permission
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // Permission might already be taken or not available
        }
    }

    private fun getStoredTreeUri(): Uri? {
        val uriString = prefs.getString(DOCUMENT_TREE_URI_KEY, null) ?: return null
        return try {
            Uri.parse(uriString)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun appendToCaptureFile(content: String) = withContext(Dispatchers.IO) {
        val treeUri = getStoredTreeUri() ?: throw IOException("No document tree access")
        
        try {
            val documentFile = DocumentFile.fromTreeUri(context, treeUri)
            if (documentFile?.exists() != true || !documentFile.isDirectory) {
                throw IOException("Document directory not accessible")
            }

            // Find or create capture.org file
            val captureFileName = "capture.org"
            var captureFile = documentFile.listFiles().find { 
                it.name == captureFileName && it.isFile 
            }

            if (captureFile == null) {
                // Create capture.org file if it doesn't exist
                captureFile = documentFile.createFile("text/org", captureFileName)
                    ?: throw IOException("Could not create capture.org file")
                
                // Add initial content
                val initialContent = "#+TITLE: Capture\n#+AUTHOR: OrgUtil\n#+DATE: ${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())}\n\n"
                writeFile(captureFile.uri, initialContent)
            }

            // Read existing content
            val existingContent = readFile(captureFile.uri)
            
            // Append new content
            val updatedContent = existingContent + content
            writeFile(captureFile.uri, updatedContent)
            
        } catch (e: Exception) {
            throw IOException("Failed to append to capture file: ${e.message}", e)
        }
    }
}