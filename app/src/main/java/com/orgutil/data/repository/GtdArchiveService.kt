package com.orgutil.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.orgutil.data.datasource.DocumentTreeStore
import com.orgutil.domain.model.OrgDocument
import com.orgutil.domain.repository.OrgFileRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One planned DONE-subtree move: the raw subtree text between its headline
 * start and the next headline (or EOF) in its source file.
 */
data class GtdArchiveItem(
    val fileName: String,
    val uri: Uri,
    val title: String,
    val level: Int,
    val start: Int,
    val end: Int,
    val subtreeText: String
)

/** Read-only result of [GtdArchiveService.plan]; executed by [GtdArchiveService.execute]. */
data class GtdArchivePlan(
    val items: List<GtdArchiveItem>,
    val destinationPath: String = GtdArchiveService.DESTINATION_PATH
)

/**
 * One-tap "archive DONE" mirroring the Doom GTD workflow
 * (my/org-gtd-classify-target-file routes DONE to gtd/archive.org and
 * my/org-gtd-move-subtree-entries moves raw subtrees, inserting them at the
 * destination's front insertion point).
 *
 * Safety model, in order:
 * 1. Plan is read-only; nothing is written before the caller confirms.
 * 2. Execute re-reads every source and verifies each planned subtree is
 *    still byte-identical at its offsets - any drift aborts with no writes.
 * 3. The destination is written FIRST (data is duplicated, not lost), then
 *    each source is rewritten without its moved subtrees, all through the
 *    repository's verified SAF write path (read-back + index sync).
 * 4. Originals are backed up to app-private storage before the first write;
 *    any write failure restores every already-written file from those
 *    originals before reporting failure.
 *
 * CANCELLED / DROPPED items are never selected. Only exact TODO state DONE
 * moves. Entries nested inside another DONE entry are not selected twice -
 * the outer subtree moves as a whole, children verbatim (drawers, planning
 * cookies, repeaters, nested tasks, links are raw text and untouched).
 * gtd/archive.org itself is never a source, so re-running is idempotent.
 */
@Singleton
class GtdArchiveService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documentTreeStore: DocumentTreeStore,
    private val orgFileRepository: OrgFileRepository
) {

    /** Read-only scan of the active GTD files for DONE subtrees. */
    suspend fun plan(): Result<GtdArchivePlan> = withContext(Dispatchers.IO) {
        runCatching {
            val root = documentTreeStore.requireTreeDocumentFile()
            val gtdDir = root.findFile(GTD_DIR_NAME)
                ?.takeIf { it.isDirectory }
                ?: throw IllegalStateException("gtd directory not found under the notes root")

            val items = mutableListOf<GtdArchiveItem>()
            SOURCE_FILE_NAMES.forEach { fileName ->
                val file = gtdDir.findFile(fileName)?.takeIf { it.isFile } ?: return@forEach
                items += scanDoneSubtrees(fileName, file.uri, readRaw(file.uri))
            }
            if (items.size > MAX_ITEMS) {
                throw IllegalStateException(
                    "Found ${items.size} DONE items (cap is $MAX_ITEMS). " +
                        "Archive in smaller batches from the desktop first."
                )
            }
            GtdArchivePlan(items = items)
        }
    }

    /**
     * Moves the planned subtrees to gtd/archive.org. The destination is
     * appended first, then sources are rewritten; on any failure every
     * already-written file is restored from the pre-run backup.
     */
    suspend fun execute(plan: GtdArchivePlan): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            if (plan.items.isEmpty()) return@runCatching 0

            val root = documentTreeStore.requireTreeDocumentFile()
            val gtdDir = root.findFile(GTD_DIR_NAME)
                ?.takeIf { it.isDirectory }
                ?: throw IllegalStateException("gtd directory not found under the notes root")

            // (2) Re-read and verify: every planned subtree must still be
            // byte-identical where it was planned, or we touch nothing.
            val byUri = plan.items.groupBy { it.uri }
            val currentContents = mutableMapOf<Uri, String>()
            for ((uri, items) in byUri) {
                val content = readRaw(uri)
                currentContents[uri] = content
                items.forEach { item ->
                    val stillThere = item.end <= content.length &&
                        content.substring(item.start, item.end) == item.subtreeText
                    if (!stillThere) {
                        throw IllegalStateException(
                            "${item.fileName} changed since the archive was planned " +
                                "(\"${item.title.take(60)}\"). Nothing was written; reopen Agenda and retry."
                        )
                    }
                }
            }

            // (4) Pre-run backup of every file this run can modify.
            val backupDir = File(
                context.filesDir,
                "gtd-archive-backup/" + System.currentTimeMillis()
            )
            val backupRoot = backupDir.apply { mkdirs() }
            // A missing archive.org is seeded with the canonical Doom
            // skeleton (my/org-ensure-gtd-files) so filetags stay :archive:.
            val existingArchive = gtdDir.findFile(ARCHIVE_FILE_NAME)?.takeIf { it.isFile }
            val archiveFile = existingArchive
                ?: gtdDir.createFile("text/org", ARCHIVE_FILE_NAME)
                    ?: throw IllegalStateException("Could not create $DESTINATION_PATH via SAF")
            val originalArchive = existingArchive?.let { readRaw(it.uri) } ?: CANONICAL_ARCHIVE_SKELETON
            writeBackup(backupRoot, ARCHIVE_FILE_NAME, originalArchive)
            byUri.keys.forEach { uri ->
                val name = byUri[uri]?.first()?.fileName ?: "source"
                writeBackup(backupRoot, name, currentContents[uri].orEmpty())
            }

            val writtenOriginals = mutableListOf<Pair<Uri, String>>()
            try {
                // (3a) Destination first: duplicate, never lose.
                val newArchive = insertAtFront(originalArchive, plan.items.map { it.subtreeText })
                writeVerified(archiveFile, ARCHIVE_FILE_NAME, newArchive)
                writtenOriginals += archiveFile.uri to originalArchive

                // (3b) Rewrite each source without its moved subtrees.
                var moved = 0
                for ((uri, items) in byUri) {
                    val content = currentContents.getValue(uri)
                    val updated = removeRanges(content, items.map { it.start to it.end })
                    val name = items.first().fileName
                    writeVerified(uriToDocumentFile(uri), name, updated)
                    writtenOriginals += uri to content
                    moved += items.size
                }
                moved
            } catch (e: Exception) {
                // Roll back everything this run already wrote, best effort;
                // the on-disk backup stays for manual recovery.
                writtenOriginals.forEach { (uri, original) ->
                    runCatching { writeVerified(uriToDocumentFile(uri), "restore", original) }
                }
                throw IllegalStateException(
                    "Archive failed and was rolled back: ${e.message}. " +
                        "A pre-run copy of every touched file is kept in ${backupRoot.absolutePath}.",
                    e
                )
            }
        }
    }

    // ---- planning helpers ----

    private fun scanDoneSubtrees(fileName: String, uri: Uri, content: String): List<GtdArchiveItem> {
        val matches = HEADLINE_REGEX.findAll(content).toList()
        val items = mutableListOf<GtdArchiveItem>()
        var coveredUntil = -1
        matches.forEachIndexed { index, match ->
            val start = match.range.first
            if (start < coveredUntil) return@forEachIndexed // nested in a selected DONE subtree
            val end = matches.getOrNull(index + 1)?.range?.first ?: content.length
            val remainder = match.groupValues[2].trim()
            val todo = todoKeywordOf(remainder) ?: return@forEachIndexed
            if (todo != DONE_KEYWORD) return@forEachIndexed
            items += GtdArchiveItem(
                fileName = fileName,
                uri = uri,
                title = stripTags(remainder).substringAfter("$todo ").trim(),
                level = match.groupValues[1].length,
                start = start,
                end = end,
                subtreeText = content.substring(start, end)
            )
            coveredUntil = end
        }
        return items
    }

    private fun todoKeywordOf(remainder: String): String? {
        val withoutTags = TAGS_REGEX.find(remainder)?.value?.let { remainder.removeSuffix(it) } ?: remainder
        return withoutTags.trim().substringBefore(" ").takeIf { it in TODO_KEYWORDS }
    }

    private fun stripTags(remainder: String): String =
        TAGS_REGEX.find(remainder)?.value?.let { remainder.removeSuffix(it).trimEnd() } ?: remainder

    // ---- text surgery (offsets, never reformatting) ----

    /** Removes [ranges] (start,end offsets) from [content], highest offset first. */
    private fun removeRanges(content: String, ranges: List<Pair<Int, Int>>): String {
        val sorted = ranges.sortedByDescending { it.first }
        var updated = content
        for ((start, end) in sorted) {
            updated = updated.substring(0, start) + updated.substring(end)
        }
        return updated
    }

    /**
     * Mirrors my/org-gtd-front-insertion-point: skip blank/#+ front matter,
     * skip past a leading structural heading, then insert each block (raw
     * subtree text) separated by blank lines.
     */
    private fun insertAtFront(archiveContent: String, blocks: List<String>): String {
        val point = frontInsertionPoint(archiveContent)
        val prefix = archiveContent.substring(0, point)
        val suffix = archiveContent.substring(point)
        val sb = StringBuilder()
        sb.append(prefix)
        if (prefix.isNotEmpty() && !prefix.endsWith('\n')) sb.append('\n')
        blocks.forEachIndexed { index, block ->
            if (index > 0) sb.append('\n')
            sb.append(block.trimEnd('\n')).append('\n')
        }
        if (suffix.isNotBlank() && !suffix.startsWith("\n")) sb.append('\n')
        sb.append(suffix)
        return sb.toString()
    }

    /**
     * Offset just past leading front matter (blank/`#+` lines) and one
     * leading structural heading, mirroring my/org-gtd-front-insertion-point.
     */
    private fun frontInsertionPoint(content: String): Int {
        var lineStart = 0
        var sawStructural = false
        while (lineStart < content.length) {
            val lineEnd = content.indexOf('\n', lineStart).let { if (it == -1) content.length else it }
            val line = content.substring(lineStart, lineEnd)
            val structuralTitle = HEADING_LINE_REGEX.find(line)?.groupValues?.get(1)?.trim()
            when {
                !sawStructural && (line.isBlank() || line.startsWith("#+")) -> Unit // front matter
                !sawStructural && structuralTitle != null && structuralTitle in STRUCTURAL_HEADINGS ->
                    sawStructural = true
                else -> return lineStart
            }
            if (lineEnd == content.length) return content.length
            lineStart = lineEnd + 1
        }
        return content.length
    }

    // ---- SAF plumbing ----

    /** Repository write path: SAF write + read-back verification + index sync. */
    private suspend fun writeVerified(file: DocumentFile, fileName: String, content: String) {
        val result = orgFileRepository.writeOrgFile(
            OrgDocument(
                uri = file.uri,
                fileName = fileName,
                content = content,
                lastModified = System.currentTimeMillis(),
                nodes = emptyList(),
                preamble = ""
            )
        )
        result.getOrThrow()
    }

    private suspend fun uriToDocumentFile(uri: Uri): DocumentFile {
        val file = DocumentFile.fromSingleUri(context, uri)
        require(file != null && file.exists()) { "Document no longer exists: $uri" }
        return file
    }

    /** Raw SAF read (no org-java parse): archiving must tolerate any content. */
    private suspend fun readRaw(uri: Uri): String {
        val file = DocumentFile.fromSingleUri(context, uri)
        require(file != null && file.exists()) { "Document no longer exists: $uri" }
        return context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } ?: throw IllegalStateException("Could not open $uri for reading")
    }

    private fun writeBackup(dir: File, fileName: String, content: String) {
        val target = File(dir, fileName.replace('/', '_'))
        target.writeText(content)
    }

    companion object {
        const val DESTINATION_PATH = "gtd/archive.org"
        const val DONE_KEYWORD = "DONE"
        const val MAX_ITEMS = 200

        private const val GTD_DIR_NAME = "gtd"
        private const val ARCHIVE_FILE_NAME = "archive.org"

        /** What my/org-ensure-gtd-files writes for a brand-new archive.org. */
        private val CANONICAL_ARCHIVE_SKELETON =
            "#+TITLE: GTD Archive\n#+FILETAGS: :gtd:archive:\n\n* Archive\n"

        /** The active GTD control files; gtd/archive.org is never a source. */
        val SOURCE_FILE_NAMES = listOf(
            "inbox.org",
            "gtd.org",
            "areas.org",
            "projects.org",
            "someday.org",
            "tickler.org",
            "routines.org"
        )

        /** my/org-gtd-structural-heading-titles (front-matter of control files). */
        private val STRUCTURAL_HEADINGS = setOf(
            "Inbox",
            "Actions",
            "Active Areas",
            "Active Projects",
            "Someday / Maybe",
            "Tickler",
            "Routines",
            "Archive"
        )

        /** Doom org-todo-keywords: match the agenda parser's keyword set. */
        private val TODO_KEYWORDS = setOf(
            "TODO", "NEXT", "VIBING", "SANDBAGGING", "WAIT", "HOLD",
            "PROJ", "AREA", "MAYBE", "DONE", "CANCELLED", "DROPPED"
        )

        private val HEADLINE_REGEX = Regex("""(?m)^(\*+)\s+(.+)$""")
        private val HEADING_LINE_REGEX = Regex("""^\*+\s+(.+)$""")
        private val TAGS_REGEX = Regex("""\s+:[A-Za-z0-9_@#%:.-]+:$""")
    }
}
