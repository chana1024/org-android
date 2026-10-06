package com.orgutil.data.repository

import com.orgutil.domain.agenda.OrgAgendaEntry
import com.orgutil.domain.agenda.OrgAgendaParser
import com.orgutil.domain.model.OrgDocument
import com.orgutil.domain.repository.OrgFileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One planning-keyword choice from the Agenda planning editor: set this
 * keyword's timestamp, clear the keyword entirely, or leave it exactly as
 * parsed. [SetDate.time] null (or omitted) means "no time given" and
 * serializes as [PLANNING_DEFAULT_TIME].
 */
sealed interface PlanningDateEdit {
    data class SetDate(val date: LocalDate, val time: LocalTime? = null) : PlanningDateEdit
    object Clear : PlanningDateEdit

    /**
     * Leaves the keyword's bytes untouched — the SCHEDULED-only quick action
     * uses it for DEADLINE so only the scheduled stamp is rewritten.
     */
    object Keep : PlanningDateEdit
}

/** Time written when the editor supplies only a calendar date. */
val PLANNING_DEFAULT_TIME: LocalTime = LocalTime.of(8, 0)

/**
 * Surgical SCHEDULED / DEADLINE editor for Org headings opened from Agenda
 * ([setPlanningDates]) — the ordinary planning-date counterpart of
 * [OrgHeadingStyleService]'s habit setup, fully independent of it.
 *
 * Setting a timestamp writes an active Org stamp with an explicit time of
 * day (`<yyyy-MM-dd EEE HH:mm>`, exactly the grammar [OrgAgendaParser] reads);
 * a date chosen without a time defaults to [PLANNING_DEFAULT_TIME] (08:00).
 * When the keyword already has a timestamp, its date and time head is
 * rewritten with the requested values; repeaters (`.+1w`, `++1m`, …) and
 * deadline windows are carried over verbatim, so a habit's repeating
 * SCHEDULED keeps its cadence. Clearing removes just that keyword's segment
 * — a standalone planning line disappears whole, a combined line
 * (`DEADLINE: <..> SCHEDULED: <..>`) keeps its other segments; a repeating
 * SCHEDULED can never be cleared here because that would silently flatten
 * a habit.
 *
 * Only the selected heading's own region is edited: the entry's own slice —
 * heading line up to the next heading of any level, the same slice
 * [OrgAgendaParser] reads — so CLOSED, LOGBOOK, drawers, title, TODO,
 * tags, body and child headings are preserved. The heading is located
 * through the exact source identity (offset, level, title offset) captured
 * at parse time and re-verified against a fresh read, so a same-titled
 * sibling can never be touched. Before anything is persisted the edited
 * file must re-parse as exactly the requested planning dates AND times,
 * with every other heading's parsed fields (and habit, when present)
 * unchanged. All writes go through [OrgFileRepository.writeOrgFile], which
 * SAF-writes, verifies by read-back and syncs the search index.
 */
@Singleton
class OrgPlanningDateService @Inject constructor(
    private val orgFileRepository: OrgFileRepository,
    private val parser: OrgAgendaParser
) {

    /**
     * Applies [scheduled] and [deadline] — each independently [PlanningDateEdit.SetDate],
     * [PlanningDateEdit.Clear] or [PlanningDateEdit.Keep] — to the exact heading
     * backing [entry].
     *
     * @throws IllegalStateException when the file changed since the agenda was
     *   loaded (identity no longer matches), the requested clear would remove
     *   a repeater, or the edited file does not re-parse as exactly the
     *   requested planning dates — nothing is persisted in any of these cases.
     */
    suspend fun setPlanningDates(
        entry: OrgAgendaEntry,
        scheduled: PlanningDateEdit,
        deadline: PlanningDateEdit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(entry.sourceOffset >= 0 && entry.titleOffset >= 0) { HEADING_UNTRACKED }

            // Always work on a fresh read: the rendered agenda may be stale.
            val content = orgFileRepository.readOrgFile(entry.uri).getOrThrow().content

            // Locate the exact heading line via the entry offsets captured at
            // parse time; region-matching the title guarantees this is the
            // selected heading, not a similarly named one.
            val lineStart = content.lastIndexOf('\n', startIndex = entry.sourceOffset)
                .let { if (it == -1) 0 else it + 1 }
            val stars = "*".repeat(entry.level)
            check(content.startsWith(stars, startIndex = lineStart)) { HEADING_MOVED }
            check(entry.titleOffset in lineStart until content.length) { HEADING_MOVED }
            check(
                entry.titleOffset + entry.title.length <= content.length &&
                    content.regionMatches(entry.titleOffset, entry.title, 0, entry.title.length)
            ) { HEADING_MOVED }
            val prefix = content.substring(lineStart, entry.titleOffset)
            val tokens = prefix.trim().split(WHITESPACE).filter { it.isNotBlank() }
            check(tokens.firstOrNull() == stars) { HEADING_MOVED }
            if (entry.todo != null) {
                check(tokens.getOrNull(1) == entry.todo) { HEADING_MOVED }
            }

            val subtreeEnd = nextHeadingStart(content, lineStart)
            var subtree = content.substring(lineStart, subtreeEnd)
            subtree = applyEdit(subtree, "SCHEDULED", scheduled)
            subtree = applyEdit(subtree, "DEADLINE", deadline)
            val newContent = content.substring(0, lineStart) + subtree + content.substring(subtreeEnd)
            if (newContent == content) return@runCatching

            verifyPlanning(content, newContent, lineStart, entry, scheduled, deadline)

            orgFileRepository.writeOrgFile(
                OrgDocument(
                    uri = entry.uri,
                    fileName = entry.fileName,
                    content = newContent,
                    lastModified = System.currentTimeMillis(),
                    nodes = emptyList(),
                    preamble = ""
                )
            ).getOrThrow()
        }
    }

    // ---- subtree editors ------------------------------------------------------

    /** Applies one keyword's edit inside the heading's own subtree. */
    private fun applyEdit(subtree: String, keyword: String, edit: PlanningDateEdit): String {
        val match = planningSegmentRegex(keyword).find(subtree)
        return when (edit) {
            PlanningDateEdit.Keep -> subtree

            is PlanningDateEdit.SetDate -> {
                // A date-only choice defaults to 08:00, so every stamp this
                // path writes carries an explicit, agenda-meaningful time.
                val stamp = formatStamp(edit.date, edit.time ?: PLANNING_DEFAULT_TIME)
                if (match == null) {
                    // New planning line below the heading and any planning
                    // lines already there, ahead of any property drawer
                    // (Org's planning-before-properties layout), mirroring
                    // the habit write path.
                    insertBelowPlanning(subtree, "$keyword: <$stamp>")
                } else {
                    // Rewrite the timestamp's date and time head; everything
                    // after them (repeater, window, warning marks) keeps its
                    // exact bytes.
                    val bodyRange = match.groups[2]?.range ?: return subtree
                    val body = match.groupValues[2]
                    val head = TIMESTAMP_HEAD_REGEX.find(body) ?: return subtree
                    var afterHead = body.substring(head.range.last + 1)
                    // Drop the existing time-of-day, if any, so the stamp
                    // ends up with exactly the requested one.
                    TIME_OF_DAY_REGEX.find(afterHead)?.let { existingTime ->
                        afterHead = afterHead.substring(existingTime.range.last + 1)
                    }
                    subtree.replaceRange(bodyRange, stamp + afterHead)
                }
            }

            PlanningDateEdit.Clear -> {
                if (match == null) return subtree
                // A repeater lives on this timestamp: removing it would
                // flatten a repeating (habit) SCHEDULED — refuse outright.
                check(REPEATER_REGEX.find(match.groupValues[2]) == null) { REPEATING_CLEAR }
                removeSegment(subtree, match)
            }
        }
    }

    /**
     * Removes the matched keyword's segment from its planning line. The whole
     * line goes when it carried nothing but this segment; a combined line
     * keeps its other segments (and the line's indentation).
     */
    private fun removeSegment(subtree: String, match: MatchResult): String {
        val leading = match.groupValues[1]
        val segmentStart = match.range.first + leading.length
        val segmentEnd = match.range.last + 1
        // The regex is ^-anchored, so the match itself starts at the line start.
        val lineStart = match.range.first
        val lineEnd = subtree.indexOf('\n', startIndex = segmentEnd)
            .let { if (it == -1) subtree.length else it }
        val remainder = (
            subtree.substring(lineStart, segmentStart) +
                subtree.substring(segmentEnd, lineEnd)
            ).trim()
        if (remainder.isEmpty()) {
            val afterLine = if (lineEnd < subtree.length) lineEnd + 1 else subtree.length
            return subtree.substring(0, lineStart) + subtree.substring(afterLine)
        }
        val indentLength = leading.indexOfFirst { !it.isWhitespace() }
            .let { if (it == -1) leading.length else it }
        return subtree.substring(0, lineStart) +
            leading.substring(0, indentLength) + remainder + subtree.substring(lineEnd)
    }

    /**
     * Inserts [block] as its own line directly below the heading, skipping
     * over planning lines that are already there (so SCHEDULED stays ahead
     * of a newly added DEADLINE, matching Org's own layout order).
     */
    private fun insertBelowPlanning(subtree: String, block: String): String {
        var insertAt = subtree.indexOf('\n').let { if (it == -1) subtree.length else it + 1 }
        while (insertAt < subtree.length) {
            val lineEnd = subtree.indexOf('\n', startIndex = insertAt)
                .let { if (it == -1) subtree.length else it }
            if (!PLANNING_LINE_REGEX.matches(subtree.substring(insertAt, lineEnd))) break
            insertAt = if (lineEnd < subtree.length) lineEnd + 1 else subtree.length
        }
        val atEof = insertAt == subtree.length
        val lead = if (atEof && subtree.lastOrNull() != '\n') "\n" else ""
        val trailing = if (!atEof || subtree.lastOrNull() == '\n') "\n" else ""
        return subtree.substring(0, insertAt) + lead + block + trailing + subtree.substring(insertAt)
    }

    /**
     * Semantic pre-write checks through the same parser the agenda reads:
     * the edited heading must re-parse with exactly the requested planning
     * dates AND times (and keep its identity and habit, if any), and every
     * other heading must parse unchanged — offsets only shifted by the
     * inserted or removed bytes below the edited heading.
     */
    private fun verifyPlanning(
        oldContent: String,
        newContent: String,
        lineStart: Int,
        entry: OrgAgendaEntry,
        scheduled: PlanningDateEdit,
        deadline: PlanningDateEdit
    ) {
        val before = flatten(parser.parseFile(entry.uri, entry.fileName, oldContent)).toList()
        val after = flatten(parser.parseFile(entry.uri, entry.fileName, newContent)).toList()
        check(before.size == after.size) { PARSE_MISMATCH }
        val delta = newContent.length - oldContent.length

        before.zip(after).forEach { (b, a) ->
            if (b.sourceOffset == lineStart) {
                check(
                    a.sourceOffset == lineStart && a.level == entry.level &&
                        a.title == entry.title && a.todo == entry.todo
                ) { PARSE_MISMATCH }
                // Set expects the requested date/time (blank time = 08:00),
                // Clear expects absence, Keep expects the exact before-parse
                // value — so a Keep keyword's bytes provably never moved.
                val expectedScheduled = when (scheduled) {
                    is PlanningDateEdit.SetDate -> scheduled.date
                    PlanningDateEdit.Keep -> b.scheduled
                    PlanningDateEdit.Clear -> null
                }
                val expectedScheduledTime = when (scheduled) {
                    is PlanningDateEdit.SetDate -> scheduled.time ?: PLANNING_DEFAULT_TIME
                    PlanningDateEdit.Keep -> b.scheduledTime
                    PlanningDateEdit.Clear -> null
                }
                val expectedDeadline = when (deadline) {
                    is PlanningDateEdit.SetDate -> deadline.date
                    PlanningDateEdit.Keep -> b.deadline
                    PlanningDateEdit.Clear -> null
                }
                val expectedDeadlineTime = when (deadline) {
                    is PlanningDateEdit.SetDate -> deadline.time ?: PLANNING_DEFAULT_TIME
                    PlanningDateEdit.Keep -> b.deadlineTime
                    PlanningDateEdit.Clear -> null
                }
                check(
                    a.scheduled == expectedScheduled && a.deadline == expectedDeadline &&
                        a.scheduledTime == expectedScheduledTime &&
                        a.deadlineTime == expectedDeadlineTime
                ) {
                    FIELD_MISMATCH
                }
                if (b.habit != null) {
                    val habit = checkNotNull(a.habit) { HABIT_BROKEN }
                    val scheduledSet = scheduled as? PlanningDateEdit.SetDate
                    if (scheduledSet != null) {
                        check(habit.scheduled == scheduledSet.date) { HABIT_BROKEN }
                    }
                    // Repeat cadence and deadline window are never touched.
                    check(
                        habit.srType == b.habit.srType &&
                            habit.srDays == b.habit.srDays &&
                            habit.drDays == b.habit.drDays
                    ) { HABIT_BROKEN }
                }
            } else {
                val expectedOffset =
                    if (b.sourceOffset < lineStart) b.sourceOffset else b.sourceOffset + delta
                check(
                    a.sourceOffset == expectedOffset && a.level == b.level &&
                        a.title == b.title && a.todo == b.todo &&
                        a.scheduled == b.scheduled && a.deadline == b.deadline &&
                        a.scheduledTime == b.scheduledTime &&
                        a.deadlineTime == b.deadlineTime
                ) { PARSE_MISMATCH }
            }
        }
    }

    // ---- helpers --------------------------------------------------------------

    private fun flatten(entries: List<OrgAgendaEntry>): Sequence<OrgAgendaEntry> =
        entries.asSequence().flatMap { entry -> sequenceOf(entry) + flatten(entry.children) }

    /** Start of the next heading of any level, or the end of the content. */
    private fun nextHeadingStart(content: String, from: Int): Int =
        NEXT_HEADING_REGEX.find(content, from + 1)?.range?.first ?: content.length

    /** `<2026-10-03 Sat 08:00>` — exactly what OrgAgendaParser's planning regexes read. */
    private fun formatStamp(date: LocalDate, time: LocalTime): String =
        "${date.format(STAMP_DATE_FORMAT)} ${time.format(STAMP_TIME_FORMAT)}"

    /**
     * Own planning segment of [keyword]: anchored at line start, preceded only
     * by indentation and well-formed other planning segments (SCHEDULED /
     * DEADLINE / CLOSED, active `<>` or inactive `[]` timestamps) — never free
     * text inside the heading body. Group 1 holds the preceding segments,
     * group 2 the keyword's timestamp body.
     */
    private fun planningSegmentRegex(keyword: String): Regex {
        val others = (PLANNING_KEYWORDS.filter { it != keyword } + "CLOSED").joinToString("|")
        return Regex(
            """(?m)^([ \t]*(?:(?:$others):[ \t]*(?:<[^>\n]*>|\[[^\]\n]*\])[ \t]*)*)""" +
                """$keyword:[ \t]*<(\d{4}-\d{2}-\d{2}[^>\n]*)>"""
        )
    }

    private companion object {
        const val HEADING_UNTRACKED =
            "无法在源文件中定位该标题，请返回后重新打开文件再试"
        const val HEADING_MOVED =
            "文件内容已变化，标题位置不匹配；请刷新 Agenda 后重试"
        const val PARSE_MISMATCH =
            "编辑后的文件解析结果与预期不一致，已取消写入"
        const val FIELD_MISMATCH =
            "校验失败：计划日期（SCHEDULED/DEADLINE）与所选不一致，已取消写入"
        const val HABIT_BROKEN =
            "校验失败：该编辑会破坏习惯的重复规则，已取消写入"
        const val REPEATING_CLEAR =
            "该时间戳带重复周期（+n d/w/m/y），不能在此清除；请通过 HABIT 设置管理"

        /** Mirrors OrgAgendaParser.HEADLINE_REGEX for the subtree boundary. */
        val NEXT_HEADING_REGEX = Regex("""(?m)^(\*+)\s+(.+)$""")
        val WHITESPACE = Regex("\\s+")

        val PLANNING_KEYWORDS = listOf("SCHEDULED", "DEADLINE")

        /** Repeater (with optional deadline window) inside a timestamp body. */
        val REPEATER_REGEX = Regex("""(\.\+|\+\+|\+)(\d+)([dwmy])(?:\s*/\s*(\d+)([dwmy]))?""")

        /** Leading date (+ optional weekday) of a timestamp body. */
        val TIMESTAMP_HEAD_REGEX = Regex("""^\d{4}-\d{2}-\d{2}([ \t]+[A-Za-z]{3})?""")

        /** Time-of-day right after the date head, when the body carries one. */
        val TIME_OF_DAY_REGEX = Regex("""^[ \t]+[0-2]?\d:[0-5]\d""")

        /** A whole planning line (any mix of SCHEDULED/DEADLINE/CLOSED segments). */
        val PLANNING_LINE_REGEX = Regex(
            """^[ \t]*(?:(?:(?:SCHEDULED|DEADLINE):[ \t]*<[^>\n]*>|CLOSED:[ \t]*\[[^\]\n]*\])[ \t]*)+$"""
        )

        val STAMP_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd EEE", Locale.US)
        val STAMP_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    }
}
