package com.orgutil.data.datasource

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentTreeStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun storeTreeUri(uri: Uri) {
        prefs.edit().putString(DOCUMENT_TREE_URI_KEY, uri.toString()).apply()

        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Permission might already be taken or not available.
        }
    }

    fun getStoredTreeUri(): Uri? {
        val uriString = prefs.getString(DOCUMENT_TREE_URI_KEY, null) ?: return null
        return runCatching { Uri.parse(uriString) }.getOrNull()
    }

    fun hasDocumentAccess(): Boolean {
        val treeUri = getStoredTreeUri() ?: return false
        return try {
            val documentFile = DocumentFile.fromTreeUri(context, treeUri)
            documentFile?.exists() == true && documentFile.isDirectory
        } catch (_: Exception) {
            false
        }
    }

    fun requireTreeDocumentFile(): DocumentFile {
        val treeUri = getStoredTreeUri() ?: throw IllegalStateException("No document tree access")
        return DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("Document directory not accessible")
    }

    companion object {
        private const val PREFS_NAME = "org_util_prefs"
        private const val DOCUMENT_TREE_URI_KEY = "document_tree_uri"
    }
}
