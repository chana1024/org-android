package com.orgutil.data.repository

import android.net.Uri
import com.orgutil.domain.agenda.OrgAgendaEntry
import com.orgutil.domain.agenda.OrgAgendaParser
import com.orgutil.domain.model.OrgDocument
import com.orgutil.domain.repository.OrgFileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Identity of the heading a Pomodoro runs on — the [OrgAgendaEntry] source
 * fields the surgical write path re-verifies against a fresh read, minus the
 * Uri (persisted as its string form so the clock can be closed after process
 * death).
 */
data class OrgClockTarget(
    val uri: Uri,
    val fileName: String?,
    val level: Int,
    val todo: String?,
    val title: String,
    val sourceOffset: Int,
    val titleOffset: Int
) {
    companion object {
        fun from(entry: OrgAgendaEntry) = OrgClockTarget(
            uri = entry.uri,
            fileName = entry.fileName,
            level = entry.level,
            todo = entry.todo,
            title = entry.title,
            sourceOffset = entry.sourceOffset,
            titleOffset = entry.titleOffset
        )
    }
}

/**
 * Surgical org clock editor for the Pomodoro timer. Clock-in inserts an open
 * `CLOCK: [...]` line at the top of the heading's own :LOGBOOK: drawer
 * (creating the drawer in Org's planning → PROPERTIES → LOGBOOK order when
 * absent, the same anchor logic [OrgHabitRepeat] uses for state logs);
 * clock-out closes the subtree's open CLOCK line into Org's standard
 * `CLOCK: [start]--[end] =>  H:MM` form. Only the selected heading's own
 * region is touched — the heading is located through the exact source
 * identity captured at parse time and re-verified against a fresh read, and
 * before anything is persisted the edited file must re-parse with the same
 * headings (identity and count) as before. All writes go through
 * [OrgFileRepository.writeOrgFile], which SAF-writes, verifies by read-back
 * and syncs the search index.
 */
@Singleton
class OrgClockService @Inject constructor(
    private val orgFileRepository: OrgFileRepository,
    private val parser: OrgAgendaParser
) {

    /**
     * Opens a clock on the heading backing [target]. When the subtree already
     * holds an open CLOCK line (e.g. clocked in from Emacs), it is reused
     * untouched — org-pomodoro's behavior — and `false` is returned.
     *
     * @throws IllegalStateException when the file changed since the agenda was
     *   loaded (identity no longer matches) or the edited file does not
     *   re-parse identically — nothing is persisted in either case.
     */
    suspend fun clockIn(target: OrgClockTarget, start: LocalDateTime): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (lineStart, subtree) = locate(
                    target, orgFileRepository.readOrgFile(target.uri).getOrThrow().content
                )
                if (findOpenClock(subtree) != null) return@runCatching false

                val line = "CLOCK: [${formatStamp(start)}]"
                persist(lineStart, insertIntoLogbook(subtree, line), target)
                true
            }
        }

    /**
     * Closes the heading's open CLOCK line at [end], computing the duration
     * from the line's own start stamp (so a clock opened from Emacs closes
     * with the right total). No-op returning `false` when no open clock
     * remains (e.g. already closed elsewhere).
     */
    suspend fun clockOut(target: OrgClockTarget, end: LocalDateTime): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (lineStart, subtree) = locate(
                    target, orgFileRepository.readOrgFile(target.uri).getOrThrow().content
                )
                val open = findOpenClock(subtree) ?: return@runCatching false

                val openedAt = parseStamp(open.groupValues[2])
                val line = "CLOCK: [${open.groupValues[2]}]--[${formatStamp(end)}]" +
                    " =>  ${formatDuration(openedAt, end)}"
                persist(lineStart, subtree.replaceRange(open.range, line), target)
                true
            }
        }

    // ---- heading location / surgical edit -------------------------------------

    /** (lineStart of the verified heading, its subtree slice). */
    private fun locate(target: OrgClockTarget, content: String): Pair<Int, String> {
        check(target.sourceOffset >= 0 && target.titleOffset >= 0) { HEADING_UNTRACKED }
        val lineStart = content.lastIndexOf('\n', startIndex = target.sourceOffset)
            .let { if (it == -1) 0 else it + 1 }
        val stars = "*".repeat(target.level)
        check(content.startsWith(stars, startIndex = lineStart)) { HEADING_MOVED }
        check(target.titleOffset in lineStart until content.length) { HEADING_MOVED }
        check(
            target.titleOffset + target.title.length <= content.length &&
                content.regionMatches(target.titleOffset, target.title, 0, target.title.length)
        ) { HEADING_MOVED }
        val prefix = content.substring(lineStart, target.titleOffset)
        val tokens = prefix.trim().split(WHITESPACE).filter { it.isNotBlank() }
        check(tokens.firstOrNull() == stars) { HEADING_MOVED }
        if (target.todo != null) {
            check(tokens.getOrNull(1) == target.todo) { HEADING_MOVED }
        }

        val subtreeEnd = nextHeadingStart(content, lineStart)
        return lineStart to content.substring(lineStart, subtreeEnd)
    }

    /**
     * [line] into the subtree's own :LOGBOOK: drawer at its top (org's
     * newest-first clock order); the drawer is created directly below the
     * PROPERTIES drawer, or below the planning lines, or below the heading
     * line — exactly the slots Org itself puts a fresh LOGBOOK in.
     */
    private fun insertIntoLogbook(subtree: String, line: String): String {
        val drawer = LOGBOOK_LINE_REGEX.find(subtree)
        if (drawer != null) {
            val afterLine = subtree.indexOf('\n', startIndex = drawer.range.last + 1)
                .let { if (it == -1) subtree.length else it + 1 }
            return subtree.substring(0, afterLine) + line + "\n" + subtree.substring(afterLine)
        }

        val anchor = PROPERTIES_DRAWER_REGEX.find(subtree)?.range?.last?.plus(1)
            ?: run {
                // Skip the planning-line run directly under the heading.
                var insertAt = subtree.indexOf('\n').let { if (it == -1) subtree.length else it + 1 }
                while (insertAt < subtree.length) {
                    val lineEnd = subtree.indexOf('\n', startIndex = insertAt)
                        .let { if (it == -1) subtree.length else it }
                    if (!PLANNING_LINE_REGEX.matches(subtree.substring(insertAt, lineEnd))) break
                    insertAt = if (lineEnd < subtree.length) lineEnd + 1 else subtree.length
                }
                insertAt - 1
            }
        val insertAt = subtree.indexOf('\n', startIndex = anchor)
            .let { if (it == -1) subtree.length else it + 1 }
        // Heading last in file with no trailing newline: start a fresh line
        // instead of gluing the drawer onto the heading text.
        val lead = if (insertAt == subtree.length && subtree.lastOrNull() != '\n') "\n" else ""
        return subtree.substring(0, insertAt) + lead +
            ":LOGBOOK:\n$line\n:END:\n" + subtree.substring(insertAt)
    }

    /** The subtree's open (start-only) CLOCK line, if any. */
    private fun findOpenClock(subtree: String): MatchResult? =
        OPEN_CLOCK_REGEX.findAll(subtree)
            // A closed clock's range still ends at the start stamp; require
            // the line to truly end there (no "--" / "=>").
            .firstOrNull { match ->
                val rest = subtree.substring(match.range.last + 1, lineEnd(subtree, match.range.last + 1))
                rest.isBlank()
            }

    private fun lineEnd(s: String, from: Int): Int =
        s.indexOf('\n', startIndex = from).let { if (it == -1) s.length else it }

    /**
     * Writes the edited [newSubtree] (the slice starting at [lineStart]) back
     * and pre-write-verifies: the file must re-parse with the same headings —
     * same count, same identities, every other heading byte-identical modulo
     * the inserted/rewritten bytes below the edited one — before anything is
     * persisted.
     */
    private suspend fun persist(lineStart: Int, newSubtree: String, target: OrgClockTarget) {
        val fresh = orgFileRepository.readOrgFile(target.uri).getOrThrow().content
        val subtreeEnd = nextHeadingStart(fresh, lineStart)
        val newContent = fresh.substring(0, lineStart) + newSubtree + fresh.substring(subtreeEnd)

        val before = flatten(parser.parseFile(target.uri, target.fileName ?: "", fresh)).toList()
        val after = flatten(parser.parseFile(target.uri, target.fileName ?: "", newContent)).toList()
        check(before.size == after.size) { PARSE_MISMATCH }
        val delta = newContent.length - fresh.length
        before.zip(after).forEach { (b, a) ->
            if (b.sourceOffset == lineStart) {
                check(
                    a.sourceOffset == lineStart && a.level == target.level &&
                        a.title == target.title && a.todo == target.todo
                ) { PARSE_MISMATCH }
            } else {
                val expectedOffset =
                    if (b.sourceOffset < lineStart) b.sourceOffset else b.sourceOffset + delta
                check(
                    a.sourceOffset == expectedOffset && a.level == b.level &&
                        a.title == b.title && a.todo == b.todo
                ) { PARSE_MISMATCH }
            }
        }

        orgFileRepository.writeOrgFile(
            OrgDocument(
                uri = target.uri,
                fileName = target.fileName ?: "",
                content = newContent,
                lastModified = System.currentTimeMillis(),
                nodes = emptyList(),
                preamble = ""
            )
        ).getOrThrow()
    }

    private fun flatten(entries: List<OrgAgendaEntry>): Sequence<OrgAgendaEntry> =
        entries.asSequence().flatMap { entry -> sequenceOf(entry) + flatten(entry.children) }

    /** Start of the next heading of any level, or the end of the content. */
    private fun nextHeadingStart(content: String, from: Int): Int =
        NEXT_HEADING_REGEX.find(content, from + 1)?.range?.first ?: content.length

    /** `2026-10-03 Sat 14:31` — Org's inactive timestamp body. */
    private fun formatStamp(time: LocalDateTime): String =
        time.format(STAMP_FORMAT)

    private fun parseStamp(stamp: String): LocalDateTime =
        LocalDateTime.parse(stamp, STAMP_FORMAT)

    /** Org's `H:MM` clock duration (org-duration-format mm default). */
    private fun formatDuration(from: LocalDateTime, to: LocalDateTime): String {
        val minutes = java.time.Duration.between(from, to).toMinutes()
        return "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}"
    }

    private companion object {
        const val HEADING_UNTRACKED =
            "无法在源文件中定位该标题，请返回后重新打开文件再试"
        const val HEADING_MOVED =
            "文件内容已变化，标题位置不匹配；请刷新 Agenda 后重试"
        const val PARSE_MISMATCH =
            "编辑后的文件解析结果与预期不一致，已取消写入"

        /** Mirrors OrgAgendaParser.HEADLINE_REGEX for the subtree boundary. */
        val NEXT_HEADING_REGEX = Regex("""(?m)^(\*+)\s+(.+)$""")
        val WHITESPACE = Regex("\\s+")

        /** Drawer patterns mirror OrgAgendaParser / OrgHabitRepeat. */
        val PROPERTIES_DRAWER_REGEX = Regex(
            """(?ms)^[ \t]*:PROPERTIES:[ \t]*\r?\n(.*?)[ \t]*:END:"""
        )
        val LOGBOOK_LINE_REGEX = Regex("""(?m)^([ \t]*):LOGBOOK:[ \t]*$""")

        /** A CLOCK line up to its start stamp; closed by `--`. */
        val OPEN_CLOCK_REGEX = Regex("""(?m)^([ \t]*)CLOCK:[ \t]*\[([^\]\n]+)\]""")

        /** A whole planning line (any mix of SCHEDULED/DEADLINE/CLOSED segments). */
        val PLANNING_LINE_REGEX = Regex(
            """^[ \t]*(?:(?:(?:SCHEDULED|DEADLINE):[ \t]*<[^>\n]*>|CLOSED:[ \t]*\[[^\]\n]*\])[ \t]*)+$"""
        )

        val STAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd EEE HH:mm", Locale.US)
    }
}
