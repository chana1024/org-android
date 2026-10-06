package com.orgutil.data.repository

import android.net.Uri
import com.orgutil.data.mapper.OrgParserWrapper
import com.orgutil.domain.agenda.OrgAgendaEntry
import com.orgutil.domain.agenda.OrgAgendaParser
import com.orgutil.domain.model.OrgDocument
import com.orgutil.domain.model.OrgNode
import com.orgutil.domain.repository.OrgFileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Surgical habit-property editor for Org headings opened from Agenda.
 * Enabling a habit goes through [enableHabit]: the user explicitly picks the
 * scheduled start date, the repeat cadence and (optionally) a deadline
 * window, and the heading gets its own :STYLE: habit plus a SCHEDULED
 * timestamp in the exact native Org grammar the app's habit parser reads —
 * `SCHEDULED: <yyyy-MM-dd Eee .+1w[/3d]>` (repeater types `+`/`++`/.+`,
 * units d/w/m/y, optional `/(deadline window)` which must be LONGER than
 * the repeat interval, mirroring org-habit). No parallel deadline format is
 * invented. Disabling ([disableHabitStyle]) removes only the :STYLE: line
 * (plus the drawer when it becomes empty) and leaves all scheduling intact.
 *
 * Only the selected heading's own region is edited: children, other
 * properties, LOGBOOK entries, planning lines (when disabling) and every
 * unrelated byte of the file are preserved because all edits stay inside
 * the entry's own slice — heading line up to the next heading of any level,
 * the same slice [OrgAgendaParser] reads. The heading is located through
 * the exact source identity (offset, level, title offset) captured at
 * parse time and re-verified against a fresh read, so a same-titled sibling
 * can never be touched. All writes go through [OrgFileRepository.writeOrgFile],
 * which SAF-writes, verifies by read-back and syncs the search index.
 */
@Singleton
class OrgHeadingStyleService @Inject constructor(
    private val orgFileRepository: OrgFileRepository,
    private val parserWrapper: OrgParserWrapper,
    private val agendaParser: OrgAgendaParser
) {

    /** Explicit habit schedule chosen in the Agenda entry dialog. */
    data class HabitSchedule(
        val date: LocalDate,
        /** Repeater type: "+", "++" or ".+". */
        val repeaterType: String,
        val count: Int,
        /** Repeat unit: 'd', 'w', 'm' or 'y'. */
        val unit: Char,
        /** Deadline window count; null = due on the scheduled day. */
        val deadlineCount: Int? = null,
        val deadlineUnit: Char = 'd'
    ) {
        val repeaterDays: Int get() = habitDurationDays(count, unit)
        val deadlineDays: Int? get() = deadlineCount?.let { habitDurationDays(it, deadlineUnit) }
    }

    /**
     * Marks the exact heading described by [node]'s source identity as a
     * habit with the explicitly chosen [schedule]: writes :STYLE: habit
     * into the heading's own drawer (creating the drawer directly below the
     * heading when missing) and sets the heading's own SCHEDULED timestamp
     * to the schedule's repeater grammar. An existing SCHEDULED timestamp is
     * replaced by the chosen one.
     *
     * @throws IllegalArgumentException for an invalid schedule (non-positive
     *   count, or a deadline window not longer than the repeat interval).
     * @throws IllegalStateException when the file changed since the heading
     *   was rendered (identity no longer matches) or the written entry does
     *   not re-parse as the exact habit requested — nothing is persisted.
     */
    suspend fun enableHabit(
        uri: Uri,
        fileName: String,
        node: OrgNode,
        schedule: HabitSchedule
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(schedule.count >= 1) { INVALID_SCHEDULE }
            val deadlineDays = schedule.deadlineDays
            require(deadlineDays == null || deadlineDays > schedule.repeaterDays) {
                INVALID_DEADLINE_WINDOW
            }

            val slice = resolveAndSlice(uri, node)
            // Drawer first so a freshly created drawer lands directly below
            // the heading, then the SCHEDULED line in front of it (Org's
            // planning-before-properties layout).
            val newSubtree = setScheduledTimestamp(insertStyleProperty(slice.subtree), schedule)
            val newContent = slice.splice(newSubtree)

            // Semantic pre-write checks: the entry must re-parse, through
            // BOTH parsers the app uses, as exactly the habit requested.
            val viewerNode = flatten(parserWrapper.parseContent(newContent).second)
                .firstOrNull { it.sourceOffset == node.sourceOffset && it.level == node.level }
            check(viewerNode != null && viewerNode.title == node.title && viewerNode.isHabitStyle) {
                VIEWER_PARSE_MISMATCH
            }
            val agendaEntry = flattenAgenda(agendaParser.parseFile(uri, fileName, newContent))
                .firstOrNull { it.sourceOffset == node.sourceOffset && it.level == node.level }
            check(agendaEntry != null && agendaEntry.title == node.title) { AGENDA_IDENTITY_MISMATCH }
            val habit = checkNotNull(agendaEntry.habit) { HABIT_NOT_PARSED }
            check(
                habit.scheduled == schedule.date &&
                    habit.srDays == schedule.repeaterDays &&
                    habit.drDays == schedule.deadlineDays
            ) { SCHEDULE_FIELD_MISMATCH }

            writeDocument(uri, fileName, newContent)
        }
    }

    /**
     * Unmarks the heading: removes only its own :STYLE: line, keeping every
     * other property (and the drawer when other entries remain) and leaving
     * SCHEDULED/DEADLINE planning lines untouched.
     */
    suspend fun disableHabitStyle(uri: Uri, fileName: String, node: OrgNode): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val slice = resolveAndSlice(uri, node)
                val newSubtree = removeStyleProperty(slice.subtree)
                if (newSubtree == slice.subtree) return@runCatching
                val newContent = slice.splice(newSubtree)

                val viewerNode = flatten(parserWrapper.parseContent(newContent).second)
                    .firstOrNull { it.sourceOffset == node.sourceOffset && it.level == node.level }
                check(viewerNode != null && viewerNode.title == node.title && !viewerNode.isHabitStyle) {
                    STYLE_MISMATCH
                }
                writeDocument(uri, fileName, newContent)
            }
        }

    /**
     * Marks the exact heading backing [entry] (an agenda entry's captured
     * source identity) as a habit with the explicitly chosen [schedule].
     * Same verified write path as [enableHabit].
     */
    suspend fun enableHabit(
        entry: OrgAgendaEntry,
        schedule: HabitSchedule
    ): Result<Unit> = enableHabit(entry.uri, entry.fileName, entry.toIdentityNode(), schedule)

    /**
     * Unmarks the heading backing [entry]: removes only its own :STYLE:
     * line, leaving SCHEDULED and every other property intact.
     */
    suspend fun disableHabitStyle(entry: OrgAgendaEntry): Result<Unit> =
        disableHabitStyle(entry.uri, entry.fileName, entry.toIdentityNode())

    /**
     * The identity fields the verified write path needs, carried from the
     * agenda entry's parse-time offsets ([OrgNode.content] is never read
     * here — only stars/keyword/title region matching).
     */
    private fun OrgAgendaEntry.toIdentityNode(): OrgNode = OrgNode(
        level = level,
        title = title,
        content = "",
        todo = todo,
        sourceOffset = sourceOffset,
        titleOffset = titleOffset
    )

    // ---- identity + splicing -------------------------------------------------

    /**
     * Fresh read + exact-identity verification (stars, keyword token, title
     * region at the captured offsets) of the heading's own region.
     */
    private suspend fun resolveAndSlice(uri: Uri, node: OrgNode): HeadingSlice {
        check(node.sourceOffset >= 0 && node.titleOffset >= 0) { HEADING_UNTRACKED }

        // Always work on a fresh read: the rendered tree may be stale.
        val content = orgFileRepository.readOrgFile(uri).getOrThrow().content

        val lineStart = content.lastIndexOf('\n', startIndex = node.sourceOffset)
            .let { if (it == -1) 0 else it + 1 }
        val stars = "*".repeat(node.level)
        check(content.startsWith(stars, startIndex = lineStart)) { HEADING_MOVED }
        check(node.titleOffset in lineStart until content.length) { HEADING_MOVED }
        check(
            node.titleOffset + node.title.length <= content.length &&
                content.regionMatches(node.titleOffset, node.title, 0, node.title.length)
        ) { HEADING_MOVED }
        val prefix = content.substring(lineStart, node.titleOffset)
        val tokens = prefix.trim().split(WHITESPACE).filter { it.isNotBlank() }
        check(tokens.firstOrNull() == stars) { HEADING_MOVED }
        if (node.todo != null) {
            check(tokens.getOrNull(1) == node.todo) { HEADING_MOVED }
        }

        val subtreeEnd = nextHeadingStart(content, lineStart)
        return HeadingSlice(content, lineStart, subtreeEnd, content.substring(lineStart, subtreeEnd))
    }

    /** Re-splices the edited subtree at the verified position. */
    private fun HeadingSlice.splice(newSubtree: String): String =
        content.substring(0, lineStart) + newSubtree + content.substring(subtreeEnd)

    /** The verified heading region inside one specific fresh read. */
    private data class HeadingSlice(
        val content: String,
        val lineStart: Int,
        val subtreeEnd: Int,
        val subtree: String
    )

    private suspend fun writeDocument(uri: Uri, fileName: String, newContent: String) {
        orgFileRepository.writeOrgFile(
            OrgDocument(
                uri = uri,
                fileName = fileName,
                content = newContent,
                lastModified = System.currentTimeMillis(),
                nodes = emptyList(),
                preamble = ""
            )
        ).getOrThrow()
    }

    // ---- subtree editors ------------------------------------------------------

    /**
     * Writes :STYLE: habit into the subtree's own drawer, creating the
     * drawer directly below the heading line when missing.
     */
    private fun insertStyleProperty(subtree: String): String {
        val drawer = OWN_PROPERTIES_DRAWER_REGEX.find(subtree)
        if (drawer == null) {
            return insertBelowHeading(subtree, ":PROPERTIES:\n:STYLE: habit\n:END:")
        }

        val drawerText = drawer.value
        val styleLine = STYLE_LINE_REGEX.find(drawerText)
        if (styleLine != null) {
            val updated = drawerText.replaceRange(
                styleLine.range,
                styleLine.groupValues[1] + ":STYLE: habit"
            )
            return subtree.replaceRange(drawer.range, updated)
        }
        // Append before :END: like org-entry-put, matching the drawer's indent.
        val indent = drawer.groupValues[1]
        val endAt = drawerText.lastIndexOf(":END:")
        val updated = drawerText.substring(0, endAt) +
            indent + ":STYLE: habit\n" + drawerText.substring(endAt)
        return subtree.replaceRange(drawer.range, updated)
    }

    /**
     * Removes the :STYLE: line from the subtree's own drawer, preserving
     * every other property. The drawer itself is dropped only when it
     * carries nothing but the STYLE entry afterwards.
     */
    private fun removeStyleProperty(subtree: String): String {
        val drawer = OWN_PROPERTIES_DRAWER_REGEX.find(subtree) ?: return subtree
        val drawerText = drawer.value
        val styleLine = STYLE_LINE_REGEX.find(drawerText) ?: return subtree

        val withoutLine = StringBuilder(drawerText)
            .deleteRange(styleLine.range.first, styleLine.range.last + 1)
        // Also drop the newline the removed line leaves behind (if any).
        if (styleLine.range.first < withoutLine.length &&
            withoutLine[styleLine.range.first] == '\n'
        ) {
            withoutLine.deleteCharAt(styleLine.range.first)
        }
        val remaining = withoutLine.toString()

        // Empty drawer (only whitespace between :PROPERTIES: and :END:):
        // remove the whole block including its trailing newline.
        if (EMPTY_DRAWER_REGEX.matches(remaining.trim('\r'))) {
            var end = drawer.range.last + 1
            if (end < subtree.length && subtree[end] == '\n') {
                end++
            } else if (end + 1 < subtree.length && subtree[end] == '\r' && subtree[end + 1] == '\n') {
                end += 2
            }
            return subtree.substring(0, drawer.range.first) + subtree.substring(end)
        }
        return subtree.replaceRange(drawer.range, remaining)
    }

    /**
     * Sets the subtree's own SCHEDULED timestamp to the schedule's repeater
     * grammar. Only a real planning line is touched — line-anchored with at
     * most other well-formed planning segments ahead of SCHEDULED — so free
     * text in the heading body mentioning "SCHEDULED:" can never match. A
     * standalone SCHEDULED line is replaced in place (indent and anything
     * after the timestamp preserved); a combined planning line (e.g.
     * `DEADLINE: <..> SCHEDULED: <..>`) keeps its other segments and the new
     * timestamp is written as its own planning line directly below the
     * heading, since the habit parser only reads a line-anchored SCHEDULED.
     * When no SCHEDULED exists, the planning line is inserted directly below
     * the heading.
     */
    private fun setScheduledTimestamp(subtree: String, schedule: HabitSchedule): String {
        val timestamp = formatTimestamp(schedule)
        val existing = SCHEDULED_PLANNING_REGEX.find(subtree)
        if (existing != null) {
            val leading = existing.groupValues[1]
            if (leading.isBlank()) {
                return subtree.replaceRange(existing.range, "SCHEDULED: <$timestamp>")
            }
            // Combined line: strip only the SCHEDULED segment, leave the
            // other planning segments on the line untouched, then insert the
            // fresh timestamp as its own planning line below the heading.
            val withoutScheduled = subtree.removeRange(
                existing.range.first + leading.length,
                existing.range.last + 1
            )
            return insertBelowHeading(withoutScheduled, "SCHEDULED: <$timestamp>")
        }
        return insertBelowHeading(subtree, "SCHEDULED: <$timestamp>")
    }

    /** `<2026-10-03 Sat .+1w>` — exactly what OrgAgendaParser's habit regexes read. */
    private fun formatTimestamp(schedule: HabitSchedule): String {
        val window = schedule.deadlineCount?.let { "/${it}${schedule.deadlineUnit}" }.orEmpty()
        return schedule.date.format(STAMP_DATE_FORMAT) +
            " ${schedule.repeaterType}${schedule.count}${schedule.unit}$window"
    }

    /** Inserts [block] as its own line(s) directly below the heading line. */
    private fun insertBelowHeading(subtree: String, block: String): String {
        val insertAt = subtree.indexOf('\n').let { if (it == -1) subtree.length else it + 1 }
        val atEof = insertAt == subtree.length
        val lead = if (atEof && subtree.lastOrNull() != '\n') "\n" else ""
        val trailing = if (!atEof || subtree.lastOrNull() == '\n') "\n" else ""
        return subtree.substring(0, insertAt) + lead + block + trailing + subtree.substring(insertAt)
    }

    // ---- helpers --------------------------------------------------------------

    private fun flatten(nodes: List<OrgNode>): Sequence<OrgNode> =
        nodes.asSequence().flatMap { node -> sequenceOf(node) + flatten(node.children) }

    private fun flattenAgenda(entries: List<OrgAgendaEntry>): Sequence<OrgAgendaEntry> =
        entries.asSequence().flatMap { entry -> sequenceOf(entry) + flattenAgenda(entry.children) }

    /** Start of the next heading of any level, or the end of the content. */
    private fun nextHeadingStart(content: String, from: Int): Int =
        NEXT_HEADING_REGEX.find(content, from + 1)?.range?.first ?: content.length

    private companion object {
        const val HEADING_UNTRACKED =
            "无法在源文件中定位该标题，请返回后重新打开文件再试"
        const val HEADING_MOVED =
            "文件内容已变化，标题位置不匹配；请返回后重新打开文件再试"
        const val STYLE_MISMATCH =
            "习惯属性修改后解析结果与预期不一致，已取消写入"
        /** Viewer (orgzly) side: heading identity, title or STYLE=habit. */
        const val VIEWER_PARSE_MISMATCH =
            "校验失败：文件视图未按预期解析出该标题的 STYLE=habit 属性，已取消写入"
        /** Agenda side: heading identity/title could not be re-matched. */
        const val AGENDA_IDENTITY_MISMATCH =
            "校验失败：议程解析未能重新匹配到该标题，已取消写入"
        /** Agenda side: SCHEDULED repeater did not parse as a valid habit. */
        const val HABIT_NOT_PARSED =
            "校验失败：SCHEDULED 重复规则未通过议程习惯解析，已取消写入"
        /** Agenda side: habit parsed but fields differ from the choice. */
        const val SCHEDULE_FIELD_MISMATCH =
            "校验失败：习惯日程字段（日期/重复/截止窗口）与所选不一致，已取消写入"
        const val INVALID_SCHEDULE =
            "无效的习惯重复周期"
        const val INVALID_DEADLINE_WINDOW =
            "截止窗口必须长于重复周期（Org 规则：/deadline 间隔需大于 scheduled 间隔）"

        /** Mirrors OrgAgendaParser.HEADLINE_REGEX for the subtree boundary. */
        val NEXT_HEADING_REGEX = Regex("""(?m)^(\*+)\s+(.+)$""")
        val WHITESPACE = Regex("\\s+")

        // All drawer patterns mirror OrgAgendaParser so the edited region is
        // exactly the region the habit parser reads back.
        val OWN_PROPERTIES_DRAWER_REGEX = Regex(
            """(?ms)^([ \t]*):PROPERTIES:[ \t]*\r?\n(.*?)[ \t]*:END:"""
        )
        val STYLE_LINE_REGEX = Regex("""(?m)^([ \t]*):STYLE:[ \t]*.*$""")
        val EMPTY_DRAWER_REGEX = Regex("""(?s)^[ \t]*:PROPERTIES:[ \t]*\r?\n[ \t]*:END:$""")

        /**
         * Own SCHEDULED planning segment: anchored at line start, preceded
         * only by indentation and well-formed DEADLINE/CLOSED segments —
         * never free text inside the heading body. Group 1 holds the
         * preceding segments (empty for a standalone SCHEDULED line).
         */
        val SCHEDULED_PLANNING_REGEX = Regex(
            """(?m)^([ \t]*(?:(?:DEADLINE|CLOSED):[ \t]*(?:<[^>\n]*>|\[[^\]\n]*\])[ \t]*)*)SCHEDULED:[ \t]*<[^>\n]*>"""
        )

        val STAMP_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd EEE", Locale.US)
    }
}

/** org-habit-duration-to-days: d=1 w=7 m=30.4 y=365.25, floored — shared by the write path and the dialog's validation. */
fun habitDurationDays(count: Int, unit: Char): Int {
    val factor = when (unit) {
        'd' -> 1.0
        'w' -> 7.0
        'm' -> 30.4
        else -> 365.25
    }
    return Math.floor(count * factor).toInt()
}
