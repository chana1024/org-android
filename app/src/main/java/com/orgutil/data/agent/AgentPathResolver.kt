package com.orgutil.data.agent

import androidx.documentfile.provider.DocumentFile
import com.orgutil.data.datasource.DocumentTreeStore
import com.orgutil.di.IoDispatcher
import com.orgutil.domain.chat.ToolArgumentException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The structural boundary of the agent harness: every tool path is a
 * RELATIVE path that must normalize to an existing document inside the
 * stored SAF tree. This is domain scoping, not an approval - it holds in
 * APPROVAL and AUTO modes alike and is the only wall between the agent and
 * anything outside the notes tree.
 */
@Singleton
class AgentPathResolver @Inject constructor(
    private val documentTreeStore: DocumentTreeStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    /**
     * Pure validation: normalizes and vettes a relative path, returning its
     * segments. Rejects traversal, encoded separators, git internals, the
     * legacy favorites files (product decision C keeps them out of git and
     * out of the agent's reach), hidden files, and non-org extensions.
     *
     * [requireOrgExtension] is relaxed for file creation, where the .org
     * suffix may be omitted and is appended afterwards (see
     * [resolveParentForCreation]). This is structural consistency, not an
     * approval: it holds identically in APPROVAL and AUTO modes.
     */
    fun normalizeAndValidate(relativePath: String, requireOrgExtension: Boolean = true): List<String> {
        val trimmed = relativePath.trim().trimStart('/')
        val segments = trimmed.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) {
            throw ToolArgumentException("Empty path")
        }
        for (segment in segments) {
            if (segment == "." || segment == "..") {
                throw ToolArgumentException("Path traversal is not allowed: $relativePath")
            }
            if (segment.contains('\\') || segment.contains("%2F", ignoreCase = true)) {
                throw ToolArgumentException("Encoded path separators are not allowed: $relativePath")
            }
            if (segment.startsWith(".git", ignoreCase = true)) {
                throw ToolArgumentException("Git internals are not accessible: $relativePath")
            }
            if (segment.startsWith(LEGACY_FAVORITES_PREFIX, ignoreCase = true)) {
                throw ToolArgumentException("Favorites files are app-managed and not accessible: $relativePath")
            }
        }
        val fileName = segments.last()
        if (fileName.startsWith(".")) {
            throw ToolArgumentException("Hidden files are not accessible: $relativePath")
        }
        if (requireOrgExtension) {
            requireOrgExtension(fileName, relativePath)
        }
        return segments
    }

    /**
     * Validates a bare new file name for renames: no directory parts, no
     * hidden names, no git/favorites names, must be .org / .org_archive.
     */
    fun validateFileName(name: String) {
        if (name.isBlank()) throw ToolArgumentException("Empty file name")
        if (name.contains('/') || name.contains('\\') || name.contains("%2F", ignoreCase = true)) {
            throw ToolArgumentException("File name must not contain directory parts: $name")
        }
        if (name.startsWith(".git", ignoreCase = true)) {
            throw ToolArgumentException("Git internals are not accessible: $name")
        }
        if (name.startsWith(LEGACY_FAVORITES_PREFIX, ignoreCase = true)) {
            throw ToolArgumentException("Favorites files are app-managed: $name")
        }
        if (name.startsWith(".")) {
            throw ToolArgumentException("Hidden file names are not allowed: $name")
        }
        requireOrgExtension(name, name)
    }

    /** Resolves to an existing file document, or throws. */
    suspend fun resolveFile(relativePath: String): DocumentFile = withContext(ioDispatcher) {
        val segments = normalizeAndValidate(relativePath)
        val resolved = walk(segments)
            ?: throw ToolArgumentException("File not found in the notes tree: $relativePath")
        if (!resolved.isFile) {
            throw ToolArgumentException("Path is a directory, not a file: $relativePath")
        }
        resolved
    }

    /**
     * Resolves the parent directory of a to-be-created file. The final
     * segment may omit the .org suffix; the returned name is the normalized
     * file name to create (suffix appended when missing).
     */
    suspend fun resolveParentForCreation(relativePath: String): Pair<DocumentFile, String> =
        withContext(ioDispatcher) {
            val segments = normalizeAndValidate(relativePath, requireOrgExtension = false)
            val fileName = withOrgExtension(segments.last())
            val parentSegments = segments.dropLast(1)
            val parent = if (parentSegments.isEmpty()) {
                documentTreeStore.requireTreeDocumentFile()
            } else {
                walk(parentSegments)
                    ?: throw ToolArgumentException("Parent directory not found: $relativePath")
            }
            if (!parent.isDirectory) {
                throw ToolArgumentException("Parent is not a directory: $relativePath")
            }
            parent to fileName
        }

    private fun withOrgExtension(fileName: String): String {
        val lower = fileName.lowercase()
        return if (lower.endsWith(".org") || lower.endsWith(".org_archive")) fileName else "$fileName.org"
    }

    private fun requireOrgExtension(fileName: String, contextPath: String) {
        val lower = fileName.lowercase()
        if (!lower.endsWith(".org") && !lower.endsWith(".org_archive")) {
            throw ToolArgumentException("Only .org / .org_archive files are accessible: $contextPath")
        }
    }

    private fun walk(segments: List<String>): DocumentFile? {
        var current = documentTreeStore.requireTreeDocumentFile()
        if (!current.exists() || !current.isDirectory) return null
        for (segment in segments) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    private companion object {
        const val LEGACY_FAVORITES_PREFIX = ".orgutil_favorites"
    }
}
