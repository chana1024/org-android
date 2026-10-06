package com.orgutil.data.repository

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Text-level Org completion bookkeeping for ORDINARY (non-habit) headings
 * switched to a done keyword — the standard org.el `org-todo` behaviour the
 * vault's Doom config produces (org-log-done='time + org-log-into-drawer t +
 * org-log-states-order-reversed t), mirroring the conventions
 * [OrgHabitRepeat] already established for habits:
 *
 *  - a `CLOSED: [ts]` planning timestamp is added (or refreshed) — Org's
 *    canonical WHEN-was-this-completed evidence, prepended to the planning
 *    line when SCHEDULED/DEADLINE already exist, on its own line directly
 *    under the headline otherwise;
 *  - a `- State "DONE"       from "…"       [ts]` line is inserted at the
 *    TOP of the heading's own :LOGBOOK: drawer, creating the drawer after
 *    the planning/property block when missing — exactly the line
 *    [com.orgutil.domain.agenda.OrgAgendaParser] reads back as the ordinary
 *    completion date and prior-state evidence.
 *
 * All edits stay inside the entry's own region (heading line up to the next
 * heading of any level — the same slice the parser reads), so children and
 * unrelated content are never touched, and the heading line itself is left
 * verbatim for the caller's keyword rewrite.
 */
internal object OrgCompletionLog {

    /**
     * Adds/refreshes the CLOSED stamp and the state log line for a
     * transition into [doneKeyword] (any keyword after "|" of the sequence —
     * DONE, but also CANCELLED/DROPPED, which get the same bookkeeping while
     * never parsing back as successful completions).
     *
     * @param fromKeyword the heading's keyword BEFORE the change; null when
     *   it had none (logged as an empty from-state, like Org's nil).
     */
    fun apply(
        subtree: String,
        doneKeyword: String,
        fromKeyword: String?,
        now: LocalDateTime
    ): String {
        val stamp = now.format(STAMP_FORMAT)
        var text = putClosedPlanning(subtree, stamp)
        text = insertStateLogLine(text, doneKeyword, fromKeyword, stamp)
        return text
    }

    /**
     * Sets the CLOSED planning stamp to [stamp]. An existing CLOSED segment
     * on a planning line (own line, or merged anywhere among SCHEDULED/
     * DEADLINE segments) has its timestamp replaced in place; otherwise
     * CLOSED is PREPENDED to the first planning line, or inserted as a fresh
     * line directly under the headline when the entry has no planning at all
     * — where org-add-planning-property puts it.
     */
    private fun putClosedPlanning(subtree: String, stamp: String): String {
        for (match in PLANNING_ANY_LINE_REGEX.findAll(subtree)) {
            val closedAt = match.value.indexOf("CLOSED:")
            if (closedAt < 0) continue
            val stampStart = match.value.indexOf('[', closedAt)
            val stampEnd = match.value.indexOf(']', closedAt)
            if (stampStart < 0 || stampEnd <= stampStart) continue
            val absStart = match.range.first + stampStart
            val absEnd = match.range.first + stampEnd + 1
            return subtree.substring(0, absStart) + "[$stamp]" + subtree.substring(absEnd)
        }

        val headlineEnd = subtree.indexOf('\n')
            .let { if (it == -1) subtree.length else it + 1 }
        // First planning line directly under the headline (blank lines inside
        // the planning block tolerated): CLOSED merges onto it, Org-style.
        var lineStart = headlineEnd
        while (lineStart < subtree.length) {
            val lineEnd = subtree.indexOf('\n', lineStart).let {
                if (it == -1) subtree.length else it
            }
            val line = subtree.substring(lineStart, lineEnd)
            if (PLANNING_LINE_REGEX.containsMatchIn(line)) {
                return subtree.substring(0, lineStart) + "CLOSED: [$stamp] " +
                    line.trimStart() + subtree.substring(lineEnd)
            }
            if (line.isNotBlank()) break // first body content: no planning block
            lineStart = lineEnd + 1
        }
        // No planning block: own line directly under the headline. A heading
        // last-in-file without a trailing newline starts a fresh line instead
        // of gluing the stamp onto the headline text.
        val lead = if (headlineEnd == subtree.length) "\n" else ""
        return subtree.substring(0, headlineEnd) + lead + "CLOSED: [$stamp]\n" +
            subtree.substring(headlineEnd)
    }

    /**
     * Inserts the state-change line at the TOP of the entry's own LOGBOOK
     * drawer (org-log-states-order-reversed t), creating the drawer right
     * after the property/planning block like org-log-beginning does.
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

        val insertAt = insertionPointAfterPlanningAndProperties(subtree)
        // Heading last in file with no trailing newline: start a fresh line
        // instead of gluing the drawer onto the previous content.
        val lead = if (insertAt == subtree.length && subtree.lastOrNull() != '\n') "\n" else ""
        return subtree.substring(0, insertAt) + lead + ":LOGBOOK:\n" + line + "\n:END:\n" +
            subtree.substring(insertAt)
    }

    /**
     * Where a NEW LOGBOOK drawer belongs: after the property drawer when one
     * exists, else after the planning block that directly follows the
     * headline (a CLOSED stamp is part of that block), else directly after
     * the headline line.
     */
    private fun insertionPointAfterPlanningAndProperties(subtree: String): Int {
        PROPERTIES_DRAWER_REGEX.find(subtree)?.range?.last?.plus(1)?.let { afterDrawer ->
            return subtree.indexOf('\n', startIndex = afterDrawer)
                .let { if (it == -1) subtree.length else it + 1 }
        }
        var lineStart = subtree.indexOf('\n').let { if (it == -1) return subtree.length else it + 1 }
        while (lineStart < subtree.length) {
            val lineEnd = subtree.indexOf('\n', lineStart).let {
                if (it == -1) subtree.length else it
            }
            val line = subtree.substring(lineStart, lineEnd)
            if (line.isBlank() || PLANNING_BLOCK_LINE_REGEX.containsMatchIn(line)) {
                lineStart = lineEnd + 1
            } else {
                return lineStart
            }
        }
        return subtree.length
    }

    /**
     * org-log-note-headings 'state `"State %-12s from %-12S %t"` with the
     * "- " list prefix, e.g. `- State "DONE"       from "NEXT"       [...]`.
     * A null from-state logs as the empty keyword, never the string "null".
     */
    private fun stateLogLine(doneKeyword: String, fromKeyword: String?, stamp: String): String {
        val from = "\"${fromKeyword.orEmpty()}\"".padEnd(12)
        return "- State " + "\"$doneKeyword\"".padEnd(12) + " from " + from + " [$stamp]"
    }

    private val STAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd EEE HH:mm", Locale.US)

    /**
     * Any planning line (own-line or merged SCHEDULED/DEADLINE/CLOSED
     * segments) — the closed-refresh scan; prose never starts with these.
     */
    private val PLANNING_ANY_LINE_REGEX = Regex(
        """(?m)^[ \t]*(?:CLOSED:[ \t]*\[|SCHEDULED:[ \t]*<|DEADLINE:[ \t]*<).*$"""
    )

    /** A SCHEDULED/DEADLINE planning line (the block CLOSED merges into). */
    private val PLANNING_LINE_REGEX = Regex("""^[ \t]*(?:SCHEDULED|DEADLINE):[ \t]*<""")

    /**
     * Any line of the planning block under a headline — including an
     * own-line CLOSED stamp, so a freshly written CLOSED stays ABOVE the
     * LOGBOOK drawer like Org orders it.
     */
    private val PLANNING_BLOCK_LINE_REGEX = Regex(
        """^[ \t]*(?:SCHEDULED|DEADLINE):[ \t]*<|^[ \t]*CLOSED:[ \t]*\["""
    )

    // Both drawer patterns mirror OrgAgendaParser/OrgHabitRepeat so the
    // edited regions are exactly the regions the parser reads back.
    private val PROPERTIES_DRAWER_REGEX = Regex(
        """(?ms)^[ \t]*:PROPERTIES:[ \t]*\r?\n(.*?)[ \t]*:END:"""
    )
    private val LOGBOOK_LINE_REGEX = Regex("""(?m)^([ \t]*):LOGBOOK:[ \t]*$""")
}
