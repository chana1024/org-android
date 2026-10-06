package com.orgutil.data.gcal

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.orgutil.data.datasource.DocumentTreeStore
import com.orgutil.data.repository.GtdArchiveService
import com.orgutil.domain.agenda.OrgAgendaParser
import com.orgutil.domain.model.OrgDocument
import com.orgutil.domain.repository.OrgFileRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Scan aborted before any calendar operation - nothing may be deleted or updated when this is thrown. */
class GcalScanException(message: String) : IllegalStateException(message)

/** One eligible GTD entry with its Google Calendar event payload. */
data class GcalScanEvent(
    val orgId: String,
    val sourceFile: String,
    val payload: GcalEventPayload
)

data class GcalScanResult(
    val events: List<GcalScanEvent>,
    val warnings: List<String>,
    val filesScanned: Int,
    val idsCreated: Int
)

/**
 * Scans exactly the Agenda GTD source files under gtd/ and builds Google
 * Calendar payloads with the Doom sync's semantics
 * (my/org-gtd-gcal-collect-events / my/org-gtd-gcal-entry-event):
 *
 * - Sources: inbox.org, gtd.org, areas.org, projects.org, someday.org,
 *   tickler.org, routines.org - never the whole vault, never the goal file,
 *   never gtd/archive.org.
 * - Eligible: has SCHEDULED or DEADLINE (SCHEDULED preferred), TODO state not
 *   in DONE / CANCELLED / DROPPED.
 * - Calendar time rules (independent from the Agenda planning editor's 08:00
 *   default): date-only -> 10:00 Asia/Shanghai + 30 minutes; explicit start
 *   preserved; explicit end respected; timed start without end +30 minutes;
 *   an end whose time-of-day is before the start rolls to the next day
 *   (cross-midnight) instead of producing an end <= start Google would
 *   reject. Repeaters/warning deltas do not become recurrence rules.
 * - Stable Org IDs: entries without an own :ID: property get one (org-id
 *   style UUID) through a surgical property-drawer insertion that preserves
 *   every other byte, verified by re-parse and written through the repository
 *   read-back-verified SAF path. Never maps by title.
 *
 * Any unreadable source, unparseable planning timestamp or failed ID write
 * aborts the whole scan via [GcalScanException] - a partial scan must never
 * be used to delete calendar events.
 */
@Singleton
class GcalOrgScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documentTreeStore: DocumentTreeStore,
    private val orgFileRepository: OrgFileRepository
) {

    suspend fun scan(): GcalScanResult = withContext(Dispatchers.IO) {
        val root = runCatching { documentTreeStore.requireTreeDocumentFile() }
            .getOrElse { throw GcalScanException("Notes tree is not accessible: ${it.message}") }
        val gtdDir = root.findFile(GTD_DIR_NAME)?.takeIf { it.isDirectory }
            ?: throw GcalScanException("gtd directory not found under the notes root")

        val events = mutableListOf<GcalScanEvent>()
        val warnings = mutableListOf<String>()
        val seenIds = HashMap<String, String>() // orgId -> source file of the first occurrence
        var filesScanned = 0
        var idsCreated = 0

        GtdArchiveService.SOURCE_FILE_NAMES.forEach { fileName ->
            val file = gtdDir.findFile(fileName)?.takeIf { it.isFile } ?: return@forEach
            filesScanned++
            val content = runCatching { readRaw(file.uri) }.getOrElse {
                throw GcalScanException("Could not read gtd/$fileName: ${it.message}")
            }
            var parse = parseFile(content)

            // Assign missing IDs first (mirrors org-id-get-create during collect).
            val missing = parse.filter { it.eligible() && it.id == null }
            if (missing.isNotEmpty()) {
                val spliced = spliceIds(content, missing)
                val reParsed = parseFile(spliced.content)
                verifySplice(fileName, spliced, reParsed)
                writeVerified(file.uri, fileName, spliced.content)
                idsCreated += spliced.insertions.size
                warnings += "gtd/$fileName: created ${spliced.insertions.size} Org ID(s)"
                parse = reParsed
            }

            parse.filter { it.eligible() }.forEach { heading ->
                val orgId = heading.id
                    ?: throw GcalScanException("gtd/$fileName: \"${heading.title}\" still has no Org ID after assignment")
                val firstSeenIn = seenIds[orgId]
                if (firstSeenIn != null) {
                    warnings += "Duplicate Org ID $orgId in gtd/$fileName (also in $firstSeenIn); kept $firstSeenIn, skipped gtd/$fileName"
                    return@forEach
                }
                seenIds[orgId] = "gtd/$fileName"
                events += heading.toEvent(fileName, orgId)
            }
        }

        if (filesScanned == 0) {
            throw GcalScanException(
                "None of the GTD source files (${GtdArchiveService.SOURCE_FILE_NAMES.joinToString(", ")}) " +
                    "exist under gtd/ - refusing to sync"
            )
        }

        GcalScanResult(events, warnings, filesScanned, idsCreated)
    }

    // ---- raw-text parse (independent of org-java; mirrors GtdArchiveService) ----

    /** One headline with everything the Calendar sync needs, all offsets relative to the file content. */
    private data class RawHeading(
        val offset: Int,
        val level: Int,
        val todo: String?,
        val title: String,
        val scheduledRaw: String?,
        val deadlineRaw: String?,
        val id: String?,
        /** Own :PROPERTIES: drawer [start,end) offsets in the file content, null when absent. */
        val drawerRange: IntRange?,
        /** Offset a brand-new drawer should be inserted at (after the planning line / heading line). */
        val insertAt: Int,
        /** Parent titles (outermost first) - the org outline path. */
        val parentTitles: List<String>
    ) {
        fun eligible(): Boolean = planningRaw() != null &&
            (todo == null || todo !in OrgAgendaParser.DONE_KEYWORDS)

        fun planningRaw(): String? = scheduledRaw ?: deadlineRaw
    }

    private fun parseFile(content: String): List<RawHeading> {
        val matches = HEADLINE_REGEX.findAll(content).toList()
        val stack = ArrayDeque<Pair<Int, String>>() // level to clean title
        return matches.mapIndexed { index, match ->
            val start = match.range.first
            val nextStart = matches.getOrNull(index + 1)?.range?.first ?: content.length
            val level = match.groupValues[1].length
            val remainder = match.groupValues[2].trim()

            var title = TAGS_REGEX.find(remainder)?.value?.let { remainder.removeSuffix(it).trimEnd() } ?: remainder
            var todo: String? = null
            val firstToken = title.trim().substringBefore(' ')
            if (firstToken in TODO_KEYWORDS) {
                todo = firstToken
                title = title.trim().removePrefix(firstToken).trim()
            }
            PRIORITY_REGEX.find(title)?.let { title = title.removeRange(it.range).trim() }

            while (stack.isNotEmpty() && stack.last().first >= level) stack.removeLast()
            val parents = stack.map { it.second }.toList()
            stack.addLast(level to title)

            val (planning, drawer) = parsePlanningAndDrawer(content, start, nextStart)
            RawHeading(
                offset = start,
                level = level,
                todo = todo,
                title = title,
                scheduledRaw = planning.scheduled,
                deadlineRaw = planning.deadline,
                id = drawer?.id,
                drawerRange = drawer?.let { it.start..it.end },
                insertAt = planning.afterPlanning,
                parentTitles = parents
            )
        }
    }

    /**
     * Reads the heading's planning line (SCHEDULED/DEADLINE raw timestamps)
     * and its own property drawer (:ID:), mirroring Org grammar: optional
     * planning line(s) directly below the headline, then an optional
     * :PROPERTIES: drawer. Free text mentioning "SCHEDULED:" never matches.
     */
    private fun parsePlanningAndDrawer(
        content: String,
        headingStart: Int,
        subtreeEnd: Int
    ): Pair<PlanningInfo, DrawerInfo?> {
        var scheduled: String? = null
        var deadline: String? = null
        var afterPlanning = endOfHeadlineLine(content, headingStart)

        var lineStart = afterPlanning
        var drawer: DrawerInfo? = null
        while (lineStart < subtreeEnd) {
            val lineEnd = content.indexOf('\n', lineStart)
                .let { if (it == -1 || it >= subtreeEnd) subtreeEnd else it }
            val next = if (lineEnd >= subtreeEnd) subtreeEnd else lineEnd + 1
            val line = content.substring(lineStart, lineEnd).trim()
            when {
                line.isEmpty() -> break
                PLANNING_LINE_REGEX.matches(line) -> {
                    SCHEDULED_SEGMENT_REGEX.find(line)?.let { scheduled = it.groupValues[1] }
                    DEADLINE_SEGMENT_REGEX.find(line)?.let { deadline = it.groupValues[1] }
                    afterPlanning = next
                    lineStart = next
                }
                line == PROPERTIES_BEGIN -> {
                    val drawerEnd = findDrawerEnd(content, lineStart, subtreeEnd)
                    if (drawerEnd != null) {
                        val drawerText = content.substring(lineStart, drawerEnd)
                        val id = ID_LINE_REGEX.find(drawerText)?.groupValues?.get(1)?.trim()
                        drawer = DrawerInfo(lineStart, drawerEnd, id)
                    }
                    lineStart = subtreeEnd // drawer consumed (or malformed): region ends
                }
                else -> break // first content line: the planning/drawer region ends
            }
        }
        return PlanningInfo(scheduled, deadline, afterPlanning) to drawer
    }

    /** End offset of the drawer: start of the line after its :END: line. */
    private fun findDrawerEnd(content: String, drawerStart: Int, subtreeEnd: Int): Int? {
        var lineStart = drawerStart
        while (lineStart < subtreeEnd) {
            val lineEnd = content.indexOf('\n', lineStart)
                .let { if (it == -1 || it >= subtreeEnd) subtreeEnd else it }
            val line = content.substring(lineStart, lineEnd).trim()
            if (line == DRAWER_END) return if (lineEnd >= subtreeEnd) subtreeEnd else lineEnd + 1
            if (lineEnd >= subtreeEnd) return null
            lineStart = lineEnd + 1
        }
        return null
    }

    private fun endOfHeadlineLine(content: String, headingStart: Int): Int =
        content.indexOf('\n', headingStart).let { if (it == -1) content.length else it + 1 }

    // ---- event payload (my/org-gtd-gcal-entry-event) ----

    private fun RawHeading.toEvent(fileName: String, orgId: String): GcalScanEvent {
        val planning = checkNotNull(planningRaw())
        val (startIso, endIso) = runCatching { toEventTimes(planning) }
            .getOrElse { throw GcalScanException("gtd/$fileName: \"${title.take(60)}\": ${it.message}") }
        val description = buildString {
            append("Source: gtd/").append(fileName).append('\n')
            append("Outline: ").append(parentTitles.joinToString(" > ")).append('\n')
            append("Org ID: ").append(orgId).append('\n')
            append("Planning: ").append(planning)
        }
        return GcalScanEvent(
            orgId = orgId,
            sourceFile = "gtd/$fileName",
            payload = GcalEventPayload(
                summary = title,
                description = description,
                start = GcalEventDateTime(startIso, TIME_ZONE),
                end = GcalEventDateTime(endIso, TIME_ZONE),
                extendedProperties = GcalExtendedProperties(
                    GcalPrivateProperties(orgId, ORG_SOURCE)
                )
            )
        )
    }

    /**
     * (start, end) ISO strings per the Doom rules - see the class doc. An end
     * time-of-day before the start rolls to the next day; equal start/end
     * becomes +30 minutes (Google rejects end <= start).
     */
    internal fun toEventTimes(rawTimestamp: String): Pair<String, String> {
        val date = DATE_REGEX.find(rawTimestamp)
            ?: throw IllegalArgumentException("cannot parse Org timestamp \"$rawTimestamp\" (no date)")
        val year = date.groupValues[1].toInt()
        val month = date.groupValues[2].toInt()
        val day = date.groupValues[3].toInt()

        val time = TIME_REGEX.find(rawTimestamp)
        val start = if (time != null) {
            LocalDateTime.of(year, month, day, time.groupValues[1].toInt(), time.groupValues[2].toInt())
        } else {
            LocalDateTime.of(year, month, day, DEFAULT_START_HOUR, DEFAULT_START_MINUTE)
        }

        val end = when {
            time == null || time.groupValues[3].isEmpty() -> start.plusMinutes(DEFAULT_DURATION_MINUTES)
            else -> {
                val sameDay = LocalDateTime.of(
                    year, month, day, time.groupValues[3].toInt(), time.groupValues[4].toInt()
                )
                when {
                    sameDay.isBefore(start) -> sameDay.plusDays(1) // cross-midnight
                    sameDay == start -> start.plusMinutes(DEFAULT_DURATION_MINUTES)
                    else -> sameDay
                }
            }
        }
        return start.format(ISO_FORMAT) to end.format(ISO_FORMAT)
    }

    // ---- surgical Org ID insertion (own property drawer only) ----

    /** One planned :ID: insertion, recorded in the ORIGINAL content's offsets. */
    private data class SpliceInsertion(
        val heading: RawHeading,
        val uuid: String,
        /** Insertion point in the original content's coordinate space. */
        val insertAtOriginal: Int,
        /** Number of characters the insertion added. */
        val insertedLength: Int
    )

    private data class SpliceResult(
        val content: String,
        val insertions: List<SpliceInsertion>
    ) {
        /**
         * Where a heading sits in the SPLICED content: its original offset
         * plus every insertion that landed AT OR BEFORE it (an insertion at
         * offset o pushes the heading at o to o+len). A heading's own
         * insertion is always strictly after its own start, so it never
         * shifts itself, but earlier headings' insertions do.
         */
        fun shiftedOffset(originalOffset: Int): Int =
            originalOffset + insertions
                .filter { it.insertAtOriginal <= originalOffset }
                .sumOf { it.insertedLength }
    }

    /**
     * Inserts `:ID: <uuid>` for every [missing] heading: appended before the
     * own drawer's :END: (org-entry-put style) or as a fresh drawer directly
     * below the planning line. Splices apply highest-offset-first on the
     * original text so each original-coordinate insertion point stays valid;
     * every insertion is recorded so verification can map original heading
     * offsets to their shifted positions in the spliced text.
     */
    private fun spliceIds(content: String, missing: List<RawHeading>): SpliceResult {
        val planned = missing.map { it to UUID.randomUUID().toString() }
        var updated = content
        val insertions = mutableListOf<SpliceInsertion>()
        planned.sortedByDescending { (heading, _) -> heading.offset }.forEach { (heading, uuid) ->
            val drawerRange = heading.drawerRange
            if (drawerRange != null) {
                // DrawerInfo.end is exclusive (start of the line after :END:).
                val drawerText = updated.substring(drawerRange.first, drawerRange.last)
                val endLine = DRAWER_END_LINE_REGEX.find(drawerText)
                    ?: throw GcalScanException("Own drawer of \"${heading.title}\" lost its :END: line")
                val indent = endLine.groupValues[1]
                val insertOffset = drawerRange.first + endLine.range.first
                val inserted = "$indent:ID: $uuid\n"
                updated = updated.substring(0, insertOffset) + inserted + updated.substring(insertOffset)
                insertions += SpliceInsertion(heading, uuid, insertOffset, inserted.length)
            } else {
                val block = ":PROPERTIES:\n:ID: $uuid\n:END:\n"
                val insertAt = heading.insertAt.coerceIn(0, updated.length)
                val lead = if (insertAt == updated.length && updated.isNotEmpty() && updated.last() != '\n') "\n" else ""
                val inserted = lead + block
                updated = updated.substring(0, insertAt) + inserted + updated.substring(insertAt)
                insertions += SpliceInsertion(heading, uuid, insertAt, inserted.length)
            }
        }
        return SpliceResult(updated, insertions)
    }

    /**
     * Before writing: every insertion must re-parse as its intended heading -
     * located at the SHIFTED offset - now carrying exactly that fresh UUID
     * and the same title. Each UUID is generated once and inserted exactly
     * once, so any offset miscalculation surfaces as a mismatch (safe abort,
     * nothing written) rather than a wrong-heading write.
     */
    private fun verifySplice(fileName: String, splice: SpliceResult, reParsed: List<RawHeading>) {
        splice.insertions.forEach { insertion ->
            val expected = splice.shiftedOffset(insertion.heading.offset)
            val heading = reParsed.firstOrNull { it.offset == expected }
                ?: throw GcalScanException(
                    "gtd/$fileName: Org ID verification lost \"${insertion.heading.title.take(60)}\" " +
                        "(expected at shifted offset $expected) - nothing was written"
                )
            if (heading.title != insertion.heading.title || heading.id != insertion.uuid) {
                throw GcalScanException(
                    "gtd/$fileName: Org ID verification failed for \"${insertion.heading.title.take(60)}\" " +
                        "(expected ${insertion.uuid}, found ${heading.id ?: "none"}) - nothing was written"
                )
            }
        }
    }

    // ---- SAF plumbing (mirrors GtdArchiveService) ----

    private fun readRaw(uri: Uri): String {
        val file = DocumentFile.fromSingleUri(context, uri)
        if (file == null || !file.exists()) {
            throw GcalScanException("Document no longer exists: $uri")
        }
        return context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } ?: throw GcalScanException("Could not open $uri for reading")
    }

    /** Repository write path: SAF write + read-back verification + index sync. */
    private suspend fun writeVerified(uri: Uri, fileName: String, content: String) {
        try {
            orgFileRepository.writeOrgFile(
                OrgDocument(
                    uri = uri,
                    fileName = fileName,
                    content = content,
                    lastModified = System.currentTimeMillis(),
                    nodes = emptyList(),
                    preamble = ""
                )
            ).getOrThrow()
        } catch (e: Exception) {
            throw GcalScanException(
                "Failed to write Org ID into gtd/$fileName: ${e.message} - sync aborted before any calendar change"
            )
        }
    }

    private data class PlanningInfo(
        val scheduled: String?,
        val deadline: String?,
        /** Offset directly after the last planning line (new-drawer insertion point). */
        val afterPlanning: Int
    )

    private data class DrawerInfo(
        val start: Int,
        val end: Int,
        val id: String?
    )

    companion object {
        const val ORG_SOURCE = "org-android"
        const val TIME_ZONE = "Asia/Shanghai"
        private const val DEFAULT_START_HOUR = 10
        private const val DEFAULT_START_MINUTE = 0
        private const val DEFAULT_DURATION_MINUTES = 30L
        private const val GTD_DIR_NAME = "gtd"
        private const val PROPERTIES_BEGIN = ":PROPERTIES:"
        private const val DRAWER_END = ":END:"

        private val ISO_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

        /** Mirrors GtdArchiveService / OrgAgendaParser headline matching. */
        private val HEADLINE_REGEX = Regex("""(?m)^(\*+)\s+(.+)$""")
        private val TAGS_REGEX = Regex("""\s+:[A-Za-z0-9_@#%:.-]+:$""")
        private val PRIORITY_REGEX = Regex("""^\[#([A-Z])]\s*""")

        private val TODO_KEYWORDS = OrgAgendaParser.TODO_KEYWORDS_ORDERED.toSet()

        /** A full planning line: one or more SCHEDULED/DEADLINE/CLOSED segments (combined lines included). */
        private val PLANNING_LINE_REGEX =
            Regex("""(?:(?:SCHEDULED|DEADLINE|CLOSED):[ \t]*(?:<[^>\n]*>|\[[^\]\n]*\])[ \t]*)+""")
        private val SCHEDULED_SEGMENT_REGEX = Regex("""(?:^|[ \t])SCHEDULED:[ \t]*(<[^>\n]*>)""")
        private val DEADLINE_SEGMENT_REGEX = Regex("""(?:^|[ \t])DEADLINE:[ \t]*(<[^>\n]*>)""")

        private val ID_LINE_REGEX = Regex("""(?m)^[ \t]*:ID:[ \t]*(\S.*?)[ \t]*$""")
        private val DRAWER_END_LINE_REGEX = Regex("""(?m)^([ \t]*):END:[ \t]*$""")

        private val DATE_REGEX = Regex("""(\d{4})-(\d{2})-(\d{2})""")
        private val TIME_REGEX = Regex("""(\d{1,2}):(\d{2})(?:-(\d{1,2}):(\d{2}))?""")
    }
}
