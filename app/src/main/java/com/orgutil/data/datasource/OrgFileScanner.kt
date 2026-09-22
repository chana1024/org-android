package com.orgutil.data.datasource

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.orgutil.di.IoDispatcher
import com.orgutil.domain.model.OrgFileInfo
import com.orgutil.domain.search.FileListQueryMatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OrgFileScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documentTreeStore: DocumentTreeStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    suspend fun getOrgFiles(uri: Uri?, query: String?): List<OrgFileInfo> = withContext(ioDispatcher) {
        val treeUri = uri ?: documentTreeStore.getStoredTreeUri() ?: return@withContext emptyList()
        val documentFile = DocumentFile.fromTreeUri(context, treeUri)
        if (documentFile?.exists() != true || !documentFile.isDirectory) return@withContext emptyList()

        if (query.isNullOrBlank()) {
            val entries = mutableListOf<OrgFileInfo>()
            for (file in documentFile.listFiles()) {
                if (file.isDirectory) {
                    entries.add(directoryInfo(file))
                } else if (file.isFile && file.name?.endsWith(".org") == true) {
                    entries.add(fileInfo(file, file.name ?: "Unknown"))
                }
            }
            return@withContext entries.sortedWith(compareByDescending<OrgFileInfo> { it.isDirectory }.thenBy { it.name })
        }

        val results = mutableListOf<OrgFileInfo>()

        // FILE_LIST mode matches file names / relative paths only and never
        // reads file bodies (product decision A): a full-tree search must not
        // pay one SAF stream read per .org file per keystroke.
        suspend fun search(file: DocumentFile, path: String) {
            if (file.isDirectory) {
                for (child in file.listFiles()) {
                    if (child.name != null) {
                        search(child, if (path.isEmpty()) child.name!! else "$path/${child.name}")
                    }
                }
            } else if (file.isFile && file.name?.endsWith(".org") == true) {
                if (FileListQueryMatcher.matches(nameOrPath = path, query = query)) {
                    results.add(fileInfo(file, path))
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

    suspend fun getAllOrgFiles(): List<OrgFileInfo> = withContext(ioDispatcher) {
        val treeUri = documentTreeStore.getStoredTreeUri() ?: return@withContext emptyList()
        val documentFile = DocumentFile.fromTreeUri(context, treeUri)
        if (documentFile?.exists() != true || !documentFile.isDirectory) return@withContext emptyList()

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
                allFiles.add(fileInfo(file, path))
            }
        }

        for (file in documentFile.listFiles()) {
            if (file.name != null) {
                collectOrgFiles(file, file.name!!)
            }
        }
        allFiles
    }

    suspend fun getAllOrgFilesUnder(uri: Uri): List<OrgFileInfo> = withContext(ioDispatcher) {
        val documentFile = DocumentFile.fromTreeUri(context, uri)
            ?: DocumentFile.fromSingleUri(context, uri)
            ?: return@withContext emptyList()
        if (!documentFile.exists()) return@withContext emptyList()

        if (documentFile.isFile) {
            return@withContext if (documentFile.name?.endsWith(".org") == true) {
                listOf(fileInfo(documentFile, documentFile.name ?: "Unknown"))
            } else {
                emptyList()
            }
        }

        if (!documentFile.isDirectory) return@withContext emptyList()

        val scopedFiles = mutableListOf<OrgFileInfo>()

        suspend fun collectScopedOrgFiles(file: DocumentFile, path: String) {
            if (file.isDirectory) {
                for (child in file.listFiles()) {
                    if (child.name != null) {
                        val childPath = if (path.isEmpty()) child.name!! else "$path/${child.name}"
                        collectScopedOrgFiles(child, childPath)
                    }
                }
            } else if (file.isFile && file.name?.endsWith(".org") == true) {
                scopedFiles.add(fileInfo(file, path))
            }
        }

        for (file in documentFile.listFiles()) {
            if (file.name != null) {
                collectScopedOrgFiles(file, file.name!!)
            }
        }
        scopedFiles
    }

    suspend fun getFileInfo(uri: Uri): OrgFileInfo? = withContext(ioDispatcher) {
        val documentFile = DocumentFile.fromSingleUri(context, uri) ?: return@withContext null
        if (!documentFile.exists() || !documentFile.isFile) return@withContext null
        fileInfo(documentFile, documentFile.name ?: uri.lastPathSegment ?: "unknown")
    }

    private fun directoryInfo(file: DocumentFile): OrgFileInfo = OrgFileInfo(
        uri = file.uri,
        name = file.name ?: "Unknown",
        lastModified = file.lastModified(),
        size = 0,
        isDirectory = true
    )

    private fun fileInfo(file: DocumentFile, path: String): OrgFileInfo = OrgFileInfo(
        uri = file.uri,
        name = path,
        lastModified = file.lastModified(),
        size = file.length(),
        isDirectory = false
    )
}
