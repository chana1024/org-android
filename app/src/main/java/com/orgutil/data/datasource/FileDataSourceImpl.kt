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

    override suspend fun getOrgFiles(uri: Uri?, query: String?): List<OrgFileInfo> = withContext(Dispatchers.IO) {
        val treeUri = uri ?: getStoredTreeUri() ?: run {
            Log.d("FileDataSourceImpl", "No tree URI available")
            return@withContext emptyList()
        }

        Log.d("FileDataSourceImpl", "Using tree URI: $treeUri")

        try {
            val documentFile = DocumentFile.fromTreeUri(context, treeUri)
            if (documentFile?.exists() != true || !documentFile.isDirectory) {
                Log.e("FileDataSourceImpl", "Document file doesn't exist or is not directory. exists=${documentFile?.exists()}, isDirectory=${documentFile?.isDirectory}")
                return@withContext emptyList()
            }

            Log.d("FileDataSourceImpl", "Document file is valid, listing files...")

            if (query.isNullOrBlank()) {
                val entries = mutableListOf<OrgFileInfo>()
                val fileList = documentFile.listFiles()
                Log.d("FileDataSourceImpl", "Found ${fileList.size} files in directory")

                fileList.forEach { file ->
                    Log.d("FileDataSourceImpl", "Processing file: ${file.name}, isDirectory=${file.isDirectory}, isFile=${file.isFile}")
                    if (file.isDirectory) {
                        entries.add(
                            OrgFileInfo(
                                uri = file.uri,
                                name = file.name ?: "Unknown",
                                lastModified = file.lastModified(),
                                size = 0,
                                isDirectory = true
                            )
                        )
                    } else if (file.isFile && file.name?.endsWith(".org") == true) {
                        entries.add(
                            OrgFileInfo(
                                uri = file.uri,
                                name = file.name ?: "Unknown",
                                lastModified = file.lastModified(),
                                size = file.length(),
                                isDirectory = false
                            )
                        )
                    }
                }
                Log.d("FileDataSourceImpl", "Returning ${entries.size} entries")
                entries.sortedWith(compareByDescending<OrgFileInfo> { it.isDirectory }.thenBy { it.name })
            } else {
                val results = mutableListOf<OrgFileInfo>()
                
                suspend fun search(file: DocumentFile, path: String) {
                    if (file.isDirectory) {
                        for (child in file.listFiles()) {
                            if (child.name != null) {
                                search(child, if (path.isEmpty()) child.name!! else "$path/${child.name}")
                            }
                        }
                    } else if (file.isFile && file.name?.endsWith(".org") == true) {
                        val fileNameMatch = isQueryMatch(file.name!!, query)
                        if (fileNameMatch) {
                            results.add(
                                OrgFileInfo(
                                    uri = file.uri,
                                    name = path,
                                    lastModified = file.lastModified(),
                                    size = file.length(),
                                    isDirectory = false
                                )
                            )
                        } else {
                            try {
                                val content = readFile(file.uri)
                                if (isQueryMatch(content, query)) {
                                    results.add(
                                        OrgFileInfo(
                                            uri = file.uri,
                                            name = path,
                                            lastModified = file.lastModified(),
                                            size = file.length(),
                                            isDirectory = false
                                        )
                                    )
                                }
                            } catch (e: IOException) {
                                Log.e("FileDataSourceImpl", "Failed to read file for search: ${file.uri}", e)
                            }
                        }
                    }
                }

                for (file in documentFile.listFiles()) {
                    if (file.name != null) {
                        search(file, file.name!!)
                    }
                }
                results
            }
        } catch (e: Exception) {
            Log.e("FileDataSourceImpl", "Error getting org files", e)
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

    override fun storeTreeUri(uri: Uri) {
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

    override fun getStoredTreeUri(): Uri? {
        val uriString = prefs.getString(DOCUMENT_TREE_URI_KEY, null) ?: return null
        return try {
            Uri.parse(uriString)
        } catch (e: Exception) {
            null
        }
    }
    private fun findOrCreateDirectory(parentDir: DocumentFile, dirName: String): DocumentFile? {
        var directory = parentDir.findFile(dirName)
        if (directory == null || !directory.isDirectory) {
            directory = parentDir.createDirectory(dirName)
        }
        return directory
    }
    override suspend fun appendToCaptureFile(content: String) = withContext(Dispatchers.IO) {
        val treeUri = getStoredTreeUri() ?: throw IOException("No document tree access")

        try {
            val rootDocumentFile = DocumentFile.fromTreeUri(context, treeUri)
            if (rootDocumentFile?.exists() != true || !rootDocumentFile.isDirectory) {
                throw IOException("Document directory not accessible")
            }

            // 1. Find or create 'roam' directory
            val roamDir = findOrCreateDirectory(rootDocumentFile, "roam")
                ?: throw IOException("Could not create 'roam' directory")

            // 2. Find or create 'inbox' directory inside 'roam'
            val inboxDir = findOrCreateDirectory(roamDir, "inbox")
                ?: throw IOException("Could not create 'inbox' directory")

            // 3. Find or create capture.org file in roam/inbox
            val captureFileName = "capture.org"
            var captureFile = inboxDir.findFile(captureFileName)

            if (captureFile == null || !captureFile.isFile) {
                // Create capture.org file if it doesn't exist
                captureFile = inboxDir.createFile("text/org", captureFileName)
                    ?: throw IOException("Could not create capture.org file in roam/inbox")

                // Add initial content
                val initialContent = "#+TITLE: Capture\n#+AUTHOR: OrgUtil\n#+DATE: ${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())}\n\n"
                writeFile(captureFile.uri, initialContent) // Use your existing writeFile
            }

            // Read existing content
            val existingContent = readFile(captureFile.uri) // Use your existing readFile

            // Append new content
            val updatedContent = existingContent + content
            writeFile(captureFile.uri, updatedContent) // Use your existing writeFile

        } catch (e: Exception) {
            Log.e("FileDataSourceImpl", "Failed to append to capture file: ${e.message}", e)
            throw IOException("Failed to append to capture file: ${e.message}", e)
        }    }

    override suspend fun getCaptureFileSize(): Long = withContext(Dispatchers.IO) {
        val treeUri = getStoredTreeUri() ?: throw IOException("No document tree access")

        try {
            val rootDocumentFile = DocumentFile.fromTreeUri(context, treeUri)
            if (rootDocumentFile?.exists() != true || !rootDocumentFile.isDirectory) {
                throw IOException("Document directory not accessible")
            }

            // Navigate to roam/inbox
            val roamDir = rootDocumentFile.findFile("roam")
            if (roamDir == null || !roamDir.isDirectory) {
                throw IOException("roam directory not found")
            }

            val inboxDir = roamDir.findFile("inbox")
            if (inboxDir == null || !inboxDir.isDirectory) {
                throw IOException("inbox directory not found in roam")
            }

            // Find capture.org file
            val captureFileName = "capture.org"
            val captureFile = inboxDir.findFile(captureFileName)
                ?: throw IOException("capture.org file not found in roam/inbox")

            if (!captureFile.isFile) {
                throw IOException("capture.org is not a file in roam/inbox")
            }

            captureFile.length()
        } catch (e: Exception) {
            Log.e("FileDataSourceImpl", "Failed to get capture file size: ${e.message}", e)
            throw IOException("Failed to get capture file size: ${e.message}", e)
        }
    }

    override suspend fun getAllOrgFiles(): List<OrgFileInfo> = withContext(Dispatchers.IO) {
        val treeUri = getStoredTreeUri() ?: return@withContext emptyList()
        
        try {
            val documentFile = DocumentFile.fromTreeUri(context, treeUri)
            if (documentFile?.exists() != true || !documentFile.isDirectory) {
                return@withContext emptyList()
            }

            val allFiles = mutableListOf<OrgFileInfo>()
            
            suspend fun collectOrgFiles(file: DocumentFile, path: String) {
                if (file.isDirectory) {
                    for (child in file.listFiles()) {
                        if (child.name != null) {
                            val childPath = if (path.isEmpty()) child.name!! else "$path/${child.name}"
                            collectOrgFiles(child, childPath)
                        }
                    }
                } else if (file.isFile && file.name?.endsWith(".org") == true) {
                    allFiles.add(
                        OrgFileInfo(
                            uri = file.uri,
                            name = path,
                            lastModified = file.lastModified(),
                            size = file.length(),
                            isDirectory = false
                        )
                    )
                }
            }
            
            for (file in documentFile.listFiles()) {
                if (file.name != null) {
                    collectOrgFiles(file, file.name!!)
                }
            }
            
            allFiles
        } catch (e: Exception) {
            Log.e("FileDataSourceImpl", "Error getting all org files for indexing", e)
            emptyList()
        }
    }

    /**
     * Checks if the text matches the query using fuzzy matching.
     * Supports partial matching and Chinese characters.
     */
    private fun isQueryMatch(text: String, query: String): Boolean {
        val normalizedQuery = query.trim()

        if (normalizedQuery.isEmpty()) return true

        // Check if query contains Chinese characters
        val containsChinese = normalizedQuery.any { it.toString().matches("[\\u4e00-\\u9fa5]".toRegex()) }

        if (containsChinese) {
            // For Chinese characters, use case-sensitive substring matching
            // Split by spaces to handle mixed Chinese/English queries
            val queryWords = normalizedQuery.split("\\s+".toRegex()).filter { it.isNotBlank() }

            // All words should be found in the text (AND operation)
            return queryWords.all { word ->
                if (word.any { it.toString().matches("[\\u4e00-\\u9fa5]".toRegex()) }) {
                    // Chinese word - case sensitive matching
                    text.contains(word)
                } else {
                    // English word - case insensitive matching
                    text.lowercase().contains(word.lowercase())
                }
            }
        } else {
            // For non-Chinese text, use case-insensitive matching
            val normalizedText = text.lowercase()
            val normalizedQueryLower = normalizedQuery.lowercase()

            // Split query into words for fuzzy matching
            val queryWords = normalizedQueryLower.split("\\s+".toRegex()).filter { it.isNotBlank() }

            // All words should be found in the text (AND operation)
            return queryWords.all { word ->
                normalizedText.contains(word)
            }
        }
    }
}