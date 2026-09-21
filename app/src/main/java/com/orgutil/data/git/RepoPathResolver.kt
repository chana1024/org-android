package com.orgutil.data.git

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.orgutil.data.datasource.DocumentTreeStore
import com.orgutil.data.datasource.GitSyncConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the git repository root for syncing. The org notes root configured
 * through SAF is typically a subdirectory of a larger git repo, so the repo
 * root is discovered by walking up from the org directory until a `.git`
 * entry is found.
 */
@Singleton
class RepoPathResolver @Inject constructor(
    private val documentTreeStore: DocumentTreeStore,
    private val configStore: GitSyncConfigStore
) {
    private var cachedRoot: File? = null

    /**
     * Resolves the repo root in this order:
     * 1. Manual override path set in Sync settings (validated to contain .git)
     * 2. Real path derived from the SAF tree URI, walking up to find .git
     */
    suspend fun resolveRepoRoot(): Result<File> = withContext(Dispatchers.IO) {
        cachedRoot?.takeIf { it.isDirectory && File(it, GIT_DIR).exists() }
            ?.let { return@withContext Result.success(it) }

        // 1. Manual override
        configStore.getRepoRootOverride()?.let { override ->
            val candidate = File(override)
            if (File(candidate, GIT_DIR).exists()) {
                cachedRoot = candidate
                return@withContext Result.success(candidate)
            }
            return@withContext Result.failure(
                IllegalStateException("Configured repo path does not contain a .git directory: $override")
            )
        }

        // 2. Derive real path from tree URI and walk up
        val orgDir = documentTreeStore.getStoredTreeUri()
            ?.let { orgDirFromTreeUri(it) }
            ?: return@withContext Result.failure(
                IllegalStateException(
                    "Cannot determine a real filesystem path for the notes folder. " +
                        "Set the repo path in Sync settings."
                )
            )

        discoverRepoRoot(orgDir)
            ?.let {
                cachedRoot = it
                Result.success(it)
            }
            ?: Result.failure(
                IllegalStateException(
                    "No git repository found above ${orgDir.absolutePath}. " +
                        "Set the repo path in Sync settings."
                )
            )
    }

    fun clearCache() {
        cachedRoot = null
    }

    companion object {
        private const val GIT_DIR = ".git"

        /**
         * Converts a SAF tree URI from primary external storage into a real
         * directory, e.g. content://.../tree/primary%3AOrgNotes ->
         * /storage/emulated/0/OrgNotes. Returns null for non-primary storage.
         */
        fun orgDirFromTreeUri(uri: Uri): File? {
            return try {
                val docId = DocumentsContract.getTreeDocumentId(uri) ?: return null
                val rel = docId.substringAfter(':', "")
                if (rel.isEmpty() || rel == docId) return null // not "primary:" storage
                val base = Environment.getExternalStorageDirectory() ?: return null
                val dir = File(base, rel)
                dir.takeIf { it.isDirectory }
            } catch (_: Exception) {
                null
            }
        }

        /** Walks up from [from] until a directory containing `.git` is found. */
        fun discoverRepoRoot(from: File): File? {
            var dir: File? = from
            while (dir != null) {
                val git = File(dir, GIT_DIR)
                if (git.isDirectory || git.isFile) return dir // .git file = linked worktree
                dir = dir.parentFile
            }
            return null
        }
    }
}
