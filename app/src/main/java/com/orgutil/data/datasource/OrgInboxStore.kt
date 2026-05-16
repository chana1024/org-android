package com.orgutil.data.datasource

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OrgInboxStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documentTreeStore: DocumentTreeStore
) {
    suspend fun append(content: String) = withContext(Dispatchers.IO) {
        val rootDocumentFile = documentTreeStore.requireTreeDocumentFile()
        if (!rootDocumentFile.exists() || !rootDocumentFile.isDirectory) {
            throw IOException("Document directory not accessible")
        }

        val inboxFile = findOrCreateInboxFile(rootDocumentFile)
        val existingContent = readFile(inboxFile.uri)
        writeFile(inboxFile.uri, existingContent + content)
    }

    suspend fun size(): Long = withContext(Dispatchers.IO) {
        val inboxFile = findInboxOrThrow(documentTreeStore.requireTreeDocumentFile())
        inboxFile.length()
    }

    fun getInboxUri(): Uri? = runCatching {
        findInboxOrThrow(documentTreeStore.requireTreeDocumentFile()).uri
    }.getOrNull()

    private fun findInboxOrThrow(rootDocumentFile: DocumentFile): DocumentFile {
        if (!rootDocumentFile.exists() || !rootDocumentFile.isDirectory) {
            throw IOException("Document directory not accessible")
        }

        val gtdDir = rootDocumentFile.findFile(GTD_DIR_NAME)
            ?: throw IOException("gtd directory not found")
        if (!gtdDir.isDirectory) {
            throw IOException("gtd directory is not a directory")
        }

        val inboxFile = gtdDir.findFile(INBOX_FILE_NAME)
            ?: throw IOException("inbox.org file not found in gtd")
        if (!inboxFile.isFile) {
            throw IOException("inbox.org is not a file in gtd")
        }

        return inboxFile
    }

    private suspend fun findOrCreateInboxFile(rootDocumentFile: DocumentFile): DocumentFile {
        val gtdDir = rootDocumentFile.findFile(GTD_DIR_NAME)
            ?: rootDocumentFile.createDirectory(GTD_DIR_NAME)
            ?: throw IOException("Could not create 'gtd' directory")

        if (!gtdDir.isDirectory) {
            throw IOException("gtd is not a directory")
        }

        val inboxFile = gtdDir.findFile(INBOX_FILE_NAME)
            ?: gtdDir.createFile("text/org", INBOX_FILE_NAME)
            ?: throw IOException("Could not create inbox.org file in gtd")

        if (inboxFile.length() == 0L) {
            val initialContent = "#+TITLE: Inbox\n#+AUTHOR: OrgUtil\n#+DATE: ${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}\n\n"
            writeFile(inboxFile.uri, initialContent)
        }

        return inboxFile
    }

    private suspend fun readFile(uri: Uri): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
        } ?: throw IOException("Could not open file for reading")
    }

    private suspend fun writeFile(uri: Uri, content: String) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { os ->
            os.writer(Charsets.UTF_8).use { writer ->
                writer.write(content)
                writer.flush()
            }
            os.flush()
        } ?: throw IOException("Could not open file for writing - no permission or invalid URI")
    }

    companion object {
        private const val GTD_DIR_NAME = "gtd"
        private const val INBOX_FILE_NAME = "inbox.org"
    }
}
