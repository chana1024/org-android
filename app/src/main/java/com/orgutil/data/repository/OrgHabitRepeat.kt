package com.orgutil.data.repository

import com.orgutil.domain.agenda.OrgAgendaParser
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Text-level Org repeat for habit completion — what the user's Doom Emacs
 * does when a STYLE=habit entry is switched to a done keyword
 * (org.el `org-auto-repeat-maybe` / `org-repeat`, log placement per
 * `org-log-beginning`):
 *
 *  - Every repeating planning timestamp advances per its repeater:
 *    "+" shifts one interval, "++" shifts whole intervals until strictly
 *    after today, ".+" re-anchors to today then shifts once. Months/years
 *    shift on the calendar (day-of-month kept, clamped at month end) like
 *    `org-timestamp-change`.
 *  - The heading does not stay done: it is reset to the repeat-to state
 *    (a REPEAT_TO_STATE property wins, else `org-todo-repeat-to-state`
 *    "NEXT" from the Doom config).
 *  - The transition is recorded as org-log-repeat 'time +
 *    org-log-into-drawer t + org-log-states-order-reversed t do it:
 *    a `- State "DONE"       from "NEXT"       [ts]` line at the TOP of
 *    the LOGBOOK drawer (the drawer is created after the property block
 *    when missing) — exactly the line [OrgAgendaParser] reads as a habit
 *    completion date for the consistency graph.
 *  - LAST_REPEAT is refreshed and a stale CLOSED planning timestamp is
 *    dropped, like the `(org-add-planning-info nil nil 'closed)` call in
 *    `org-auto-repeat-maybe`.
 *
 * All edits stay inside the entry's own region (heading line up to the
 * next heading of any level — the same slice OrgAgendaParser parses), so
 * children and unrelated content are never touched, and the heading line
 * itself is left verbatim for the caller's keyword rewrite.
 */
internal object OrgHabitRepeat {

    data class Result(
        /** Subtree text with planning, property and log edits applied. */
        val subtree: String,
        /** Keyword the heading carries afterwards (the repeat-to state). */
        val finalKeyword: String,
        /** New SCHEDULED date after the repeat shift. */
        val newScheduled: LocalDate
    )

    fun apply(
        subtree: String,
        doneKeyword: String,
        fromKeyword: String?,
        today: LocalDate,
        now: LocalDateTime
    ): Result {
        var text = removeClosedPlanning(subtree)

        // Advance every repeating planning timestamp (org-repeat updates
        // SCHEDULED and DEADLINE alike); a habit always has the former.
        var newScheduled: LocalDate? = null
        for (keyword in PLANNING_KEYWORDS) {
            val shifted = shiftPlanningTimestamp(text, keyword, today) ?: continue
            text = shifted.first
            if (keyword == "SCHEDULED") newScheduled = shifted.second
        }
        checkNotNull(newScheduled) { NOT_A_REPEATABLE_HABIT }

        val stamp = now.format(STAMP_FORMAT)
        val finalKeyword = repeatToState(text, fromKeyword)
        text = putLastRepeat(text, stamp)
        text = insertStateLogLine(text, doneKeyword, fromKeyword, stamp)
        return Result(text, finalKeyword, newScheduled)
    }

    /**
     * org's to-state chain: REPEAT_TO_STATE property, then the configured
     * `org-todo-repeat-to-state "NEXT"`, then the previous (non-done)
     * state, then the head of the keyword sequence.
     */
    private fun repeatToState(subtree: String, fromKeyword: String?): String {
        propertyValue(subtree, "REPEAT_TO_STATE")
            ?.takeIf { it in OrgAgendaParser.TODO_KEYWORDS_ORDERED }
            ?.let { return it }
        // Doom config: (setq org-todo-repeat-to-state "NEXT" ...)
        if ("NEXT" in OrgAgendaParser.TODO_KEYWORDS_ORDERED) return "NEXT"
        return fromKeyword?.takeIf {
            it in OrgAgendaParser.TODO_KEYWORDS_ORDERED && it !in OrgAgendaParser.DONE_KEYWORDS
        } ?: OrgAgendaParser.TODO_KEYWORDS_ORDERED.first()
    }

    /** Shifts the first timestamp on the keyword's own planning line. */
    private fun shiftPlanningTimestamp(
        subtree: String,
        keyword: String,
        today: LocalDate
    ): Pair<String, LocalDate>? {
        val line = planningLineRegex(keyword).find(subtree) ?: return null
        val bodyRange = line.groups[1]?.range ?: return null
        val body = line.groupValues[1]
        val repeater = REPEATER_REGEX.find(body) ?: return null // no repeater: org leaves it alone
        val head = TIMESTAMP_HEAD_REGEX.find(body) ?: return null
        val date = runCatching {
            LocalDate.parse(head.groupValues[1], DATE_FORMAT)
        }.getOrElse { return null }

        val count = repeater.groupValues[2].toLong()
        val unit = repeater.groupValues[3].first()
        val newDate = when (repeater.groupValues[1]) {
            ".+" -> shiftOnce(today, count, unit)
            "++" -> {
                // At least one shift, then whole intervals until the date
                // is strictly after today (org prompts past 10; just cap).
                var shifted = shiftOnce(date, count, unit)
                var guard = 0
                while (shifted <= today && guard++ < MAX_SHIFTS) {
                    shifted = shiftOnce(shifted, count, unit)
                }
                shifted
            }
            else -> shiftOnce(date, count, unit)
        }

        val weekday = head.groupValues[2].takeIf { it.isNotBlank() }
            ?.let { " " + newDate.format(WEEKDAY_FORMAT) }
            .orEmpty()
        val newBody = newDate.format(DATE_FORMAT) + weekday + body.substring(head.range.last + 1)
        return subtree.replaceRange(bodyRange, newBody) to newDate
    }

    /** org-timestamp-change: d/w are day arithmetic, m/y keep the day-of-month. */
    private fun shiftOnce(date: LocalDate, count: Long, unit: Char): LocalDate = when (unit) {
        'd' -> date.plusDays(count)
        'w' -> date.plusDays(7 * count)
        'm' -> date.plusMonths(count)
        else -> date.plusYears(count)
    }

    /**
     * Drops a stale CLOSED planning segment. Org removes it because the
     * reset heading is no longer done; the pattern only matches a planning
     * line (optionally behind SCHEDULED/DEADLINE segments), never prose.
     */
    private fun removeClosedPlanning(subtree: String): String {
        val match = CLOSED_PLANNING_REGEX.find(subtree) ?: return subtree
        val lineStart = subtree.lastIndexOf('\n', startIndex = match.range.first)
            .let { if (it == -1) 0 else it + 1 }
        val lineEnd = subtree.indexOf('\n', startIndex = match.range.last + 1)
            .let { if (it == -1) subtree.length else it }
        val remainder = (
            subtree.substring(lineStart, match.range.first) +
                subtree.substring(match.range.last + 1, lineEnd)
            ).trimEnd()
        return if (remainder.isBlank()) {
            // Whole planning line is gone; drop the line and its newline.
            subtree.substring(0, lineStart) +
                subtree.substring(minOf(lineEnd + 1, subtree.length))
        } else {
            subtree.substring(0, lineStart) + remainder + subtree.substring(lineEnd)
        }
    }

    /**
     * Refreshes :LAST_REPEAT: in the entry's own property drawer (habits
     * always have one — STYLE=habit lives there), updating in place or
     * appending before :END: like org-entry-put.
     */
    private fun putLastRepeat(subtree: String, stamp: String): String {
        val drawer = PROPERTIES_DRAWER_REGEX.find(subtree) ?: error(NOT_A_REPEATABLE_HABIT)
        val drawerText = drawer.value
        val updated = LAST_REPEAT_LINE_REGEX.find(drawerText)?.let { match ->
            drawerText.replaceRange(
                match.range,
                match.groupValues[1] + " [$stamp]"
            )
        } ?: run {
            val endAt = drawerText.lastIndexOf(":END:")
            drawerText.substring(0, endAt) + ":LAST_REPEAT: [$stamp]\n" +
                drawerText.substring(endAt)
        }
        return subtree.replaceRange(drawer.range, updated)
    }

    /**
     * Inserts the state-change line at the TOP of the entry's own LOGBOOK
     * drawer (org-log-states-order-reversed t), creating the drawer right
     * after the property block like org-log-beginning does.
     */
    private fun insertStateLogLine(
        subtree: String,
        doneKeyword: String,
        fromKeyword: String?,
        stamp: String
    ): String {
        val line = stateLogLine(doneKeyword, fromKeyword, stamp)
        val drawer = LOGBOOK_LINE_REGEX.find(subtree)
        if (drawer != null) {
            val afterLine = subtree.indexOf('\n', startIndex = drawer.range.last + 1)
                .let { if (it == -1) subtree.length else it + 1 }
            val indent = drawer.groupValues[1]
            return subtree.substring(0, afterLine) + indent + line + "\n" +
                subtree.substring(afterLine)
        }

        val anchor = PROPERTIES_DRAWER_REGEX.find(subtree)?.range?.last?.plus(1)
            ?: subtree.indexOf('\n').let { if (it == -1) subtree.length else it + 1 }
        val insertAt = subtree.indexOf('\n', startIndex = anchor)
            .let { if (it == -1) subtree.length else it + 1 }
        // Heading last in file with no trailing newline: start a fresh line
        // instead of gluing the drawer onto ":END:".
        val lead = if (insertAt == subtree.length && subtree.lastOrNull() != '\n') "\n" else ""
        return subtree.substring(0, insertAt) + lead + ":LOGBOOK:\n" + line + "\n:END:\n" +
            subtree.substring(insertAt)
    }

    /**
     * org-log-note-headings 'state `"State %-12s from %-12S %t"` with the
     * "- " list prefix, e.g. `- State "DONE"       from "NEXT"       [...]`.
     */
    private fun stateLogLine(doneKeyword: String, fromKeyword: String?, stamp: String): String {
        val from = "\"$fromKeyword\"".padEnd(12)
        return "- State " + "\"$doneKeyword\"".padEnd(12) + " from " + from + " [$stamp]"
    }

    /** First :PROPERTIES: drawer of the subtree, as a key/value map. */
    private fun propertyDrawer(subtree: String): String? =
        PROPERTIES_DRAWER_REGEX.find(subtree)?.value

    private fun propertyValue(subtree: String, key: String): String? {
        val drawer = propertyDrawer(subtree) ?: return null
        val regex = Regex("""(?m)^([ \t]*):$key:[ \t]*(.*?)[ \t]*$""")
        return regex.find(drawer)?.groupValues?.get(2)
    }

    private fun planningLineRegex(keyword: String): Regex =
        Regex("""(?m)^[ \t]*$keyword:[ \t]*<([^>\n]*)>""")

    private const val MAX_SHIFTS = 10_000
    private const val NOT_A_REPEATABLE_HABIT =
        "文件内容已变化，该条目不再是可重复的习惯；请刷新 Agenda 后重试"

    private val PLANNING_KEYWORDS = listOf("SCHEDULED", "DEADLINE")
    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private val WEEKDAY_FORMAT = DateTimeFormatter.ofPattern("EEE", Locale.US)
    private val STAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd EEE HH:mm", Locale.US)

    /** Repeater inside a planning timestamp body: ".+1w", "++1m", "+1d", ... */
    private val REPEATER_REGEX = Regex("""(\.\+|\+\+|\+)(\d+)([dwmy])""")

    /** Leading date (+ optional weekday) of a timestamp body. */
    private val TIMESTAMP_HEAD_REGEX = Regex("""^\s*(\d{4}-\d{2}-\d{2})([ \t]+[A-Za-z]{3})?""")

    /** CLOSED segment of a planning line, optionally behind other segments. */
    private val CLOSED_PLANNING_REGEX = Regex(
        """(?m)^[ \t]*(?:(?:SCHEDULED|DEADLINE):[ \t]*<[^>\n]*>[ \t]*)*CLOSED:[ \t]*\[[^\]\n]*\][ \t]*$"""
    )

    // Both drawer patterns mirror OrgAgendaParser so the edited regions
    // are exactly the regions the parser reads back.
    private val PROPERTIES_DRAWER_REGEX = Regex(
        """(?ms)^[ \t]*:PROPERTIES:[ \t]*\r?\n(.*?)[ \t]*:END:"""
    )
    private val LOGBOOK_LINE_REGEX = Regex("""(?m)^([ \t]*):LOGBOOK:[ \t]*$""")
    private val LAST_REPEAT_LINE_REGEX = Regex("""(?m)^([ \t]*:LAST_REPEAT:)[ \t]*.*$""")
}
