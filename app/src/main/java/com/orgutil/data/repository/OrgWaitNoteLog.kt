package com.orgutil.data.repository

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Text-level Org bookkeeping for WAIT explanatory notes — the exact lines
 * org-store-log-note writes for the vault's `WAIT(w@)` keyword (org.el:
 * `(setq note (concat note " \\\\"))` only when note lines exist, note lines
 * indented to the list-item body column, blank lines dropped by
 * org-split-string, inserted at the drawer TOP because the Doom config has
 * org-log-states-order-reversed t):
 *
 * ```
 * :LOGBOOK:
 * - State "WAIT"       from "NEXT"       [2026-09-23 Wed 10:42] \\
 *   waiting for tongji to come back
 * :END:
 * ```
 *
 * Like [OrgCompletionLog], every edit stays inside the entry's own region
 * (heading line up to the next heading of any level) and the heading line
 * itself is left verbatim for the caller's keyword rewrite. A WAIT note is
 * NEVER completion evidence: no CLOSED stamp, no State-"DONE" line, so it
 * cannot inflate today's goal statistics.
 */
internal object OrgWaitNoteLog {

    /** Non-blank reason is required for a fresh WAIT transition. */
    const val REASON_REQUIRED = "请填写等待原因（WAIT 状态需要说明在等什么）"

    /** Upper bound keeps one write surgical; Org notes are a line or two. */
    private const val REASON_MAX_CHARS = 2000

    /**
     * Rejects reason text whose lines would corrupt structure once written
     * indented into the LOGBOOK: a bare drawer fence closes/opens drawers at
     * any indent Org recognizes, and the parser's log/planning regexes all
     * match indented lines too — a faked `- State "DONE" …` / `CLOSED:` /
     * `CLOCK:` / planning line inside a note would be read back as real
     * evidence. Returns the user-facing rejection message, or null when safe.
     */
    fun validateReason(reason: String): String? {
        if (reason.length > REASON_MAX_CHARS) {
            return "等待原因过长（${reason.length} 字符，上限 $REASON_MAX_CHARS）"
        }
        reason.split('\n').forEach { rawLine ->
            val line = rawLine.trim()
            when {
                DRAWER_FENCE_REGEX.matches(line) ->
                    return "等待原因不能包含抽屉结构行（$line）"
                STRUCTURAL_PREFIX_REGEX.containsMatchIn(line) ->
                    return "等待原因不能包含 Org 结构行（$line）— 请改写该行后保存"
            }
        }
        return null
    }

    /**
     * Inserts a fresh `- State "WAIT"       from "…"       [ts] \\` state log
     * with its note at the TOP of the heading's own LOGBOOK drawer, creating
     * the drawer right after the property/planning block when missing —
     * org-log-beginning's placement. No CLOSED stamp: WAIT is not a done
     * keyword.
     */
    fun insertTransitionNote(
        subtree: String,
        fromKeyword: String?,
        reason: String,
        now: LocalDateTime
    ): String {
        val stamp = now.format(STAMP_FORMAT)
        val notes = noteLines(reason)
        val line = stateLogLine("WAIT", fromKeyword, stamp, hasNote = notes.isNotEmpty())
        return insertLogLine(subtree, line, notes, eolOf(subtree))
    }

    /**
     * Rewrites the note of the LATEST own WAIT state log line in place —
     * its timestamp, from-keyword and every other log/CLOCK line stay
     * byte-identical (only that one state line's tail and its indented
     * continuation lines are touched). A blank [reason] strips the note
     * (marker + continuation lines), leaving the bare state line an
     * empty-prompt transition produces. When the heading carries no WAIT
     * log line at all (hand-set keyword), a fresh one is inserted with the
     * empty from-state — how Org logs a nil previous state.
     */
    fun editLatestNote(subtree: String, reason: String, now: LocalDateTime): NoteEdit {
        val notes = noteLines(reason)
        val latest = findLatestWaitLog(subtree)
        if (latest == null) {
            val stamp = now.format(STAMP_FORMAT)
            val line = stateLogLine("WAIT", fromKeyword = null, stamp = stamp, hasNote = notes.isNotEmpty())
            return NoteEdit(insertLogLine(subtree, line, notes, eolOf(subtree)), insertedFresh = true)
        }

        // In-place rewrite. The ']' closing the stamp is the line's first
        // bracket — the from-state quotes never contain one — so everything
        // before it (bullet, padding, stamp) is preserved verbatim and only
        // the tail (old marker) plus the old note lines are replaced.
        val stampClose = latest.range.first + latest.value.indexOf(']') + 1
        val lineEnd = subtree.indexOf('\n', latest.range.first)
            .let { if (it == -1) subtree.length else it }
        val eol = if (subtree.startsWith("\r\n", lineEnd)) "\r\n" else "\n"

        // The old note region ends where the parser's collection stops: the
        // first line indented no deeper than the state line, a bare :END:,
        // or EOF. Same walk, so edit and read-back always agree.
        val stateIndent = latest.groupValues[1].length
        // lineEnd == length (state line ends the region with no newline)
        // must not push regionEnd past the string.
        var regionEnd = (lineEnd + 1).coerceAtMost(subtree.length)
        while (regionEnd < subtree.length) {
            val boundary = subtree.indexOf('\n', regionEnd)
                .let { if (it == -1) subtree.length else it }
            val line = subtree.substring(regionEnd, boundary).trimEnd('\r')
            val indent = line.takeWhile { it == ' ' || it == '\t' }.length
            if (indent <= stateIndent || line.trim() == ":END:") break
            if (boundary == subtree.length) {
                regionEnd = subtree.length
                break
            }
            regionEnd = boundary + 1
        }

        val head = subtree.substring(latest.range.first, stampClose) +
            if (notes.isNotEmpty()) " \\\\" else ""
        val tail = subtree.substring(regionEnd)
        val replacement = buildString {
            append(head)
            if (lineEnd < subtree.length) {
                append(eol)
                if (notes.isNotEmpty()) {
                    append(notes.joinToString(eol) { latest.groupValues[1] + "  " + it })
                    // Keep the newline that separated the last old note line
                    // from whatever followed it (:END:, a CLOCK line, EOF's
                    // trailing newline) — never glue them together.
                    if (regionEnd > latest.range.first && subtree[regionEnd - 1] == '\n') {
                        append(eol)
                    }
                }
            } else if (notes.isNotEmpty()) {
                // State line was the last line of the region with no trailing
                // newline: the note needs one, and none is added after it.
                append(eol)
                append(notes.joinToString(eol) { latest.groupValues[1] + "  " + it })
            }
        }
        // splice: everything before the state line and from the terminator
        // line on stays byte-identical; only the line + its note region moved.
        return NoteEdit(
            subtree.substring(0, latest.range.first) + replacement + tail,
            insertedFresh = false
        )
    }

    /** Result of an in-place reason edit. */
    data class NoteEdit(
        val subtree: String,
        /** True when no WAIT log existed and a fresh line was created. */
        val insertedFresh: Boolean
    )

    /** Latest own WAIT state-log match (same selection the parser uses). */
    private fun findLatestWaitLog(subtree: String): MatchResult? =
        WAIT_STATE_LOG_REGEX.findAll(subtree)
            .filter { it.groupValues[3].isNotBlank() }
            .maxByOrNull { it.groupValues[3] }

    /** The note lines org-split-string would store: trimmed, blanks dropped. */
    private fun noteLines(reason: String): List<String> =
        reason.split('\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }

    /**
     * Canonical reason form — the EXACT string the parser reads back from
     * a written note (lines trimmed, blanks dropped, \n-joined). Callers
     * compare round-trip results against this, never against raw input.
     */
    fun normalizeReason(reason: String): String = noteLines(reason).joinToString("\n")

    /** File's dominant line ending — inserted lines must match the file. */
    private fun eolOf(subtree: String): String =
        if (subtree.contains("\r\n")) "\r\n" else "\n"

    /**
     * Inserts [line] (with its [notes] continuation lines) at the TOP of the
     * own LOGBOOK drawer, creating the drawer after the property/planning
     * block when missing — OrgCompletionLog's exact placement, so both log
     * kinds order identically in a heading.
     */
    private fun insertLogLine(subtree: String, line: String, notes: List<String>, eol: String): String {
        val drawer = LOGBOOK_LINE_REGEX.find(subtree)
        if (drawer != null) {
            val afterLine = subtree.indexOf('\n', startIndex = drawer.range.last + 1)
                .let { if (it == -1) subtree.length else it + 1 }
            val indent = drawer.groupValues[1]
            val body = buildString {
                append(indent).append(line)
                notes.forEach { note -> append(eol).append(indent).append("  ").append(note) }
                append(eol)
            }
            return subtree.substring(0, afterLine) + body + subtree.substring(afterLine)
        }

        val insertAt = insertionPointAfterPlanningAndProperties(subtree)
        // Heading last in file with no trailing newline: start a fresh line
        // instead of gluing the drawer onto the previous content.
        val lead = if (insertAt == subtree.length && subtree.lastOrNull() != '\n') eol else ""
        // The note block starts on its OWN line under the state line (eol
        // first), each continuation eol-separated — never merged into it.
        val notesBlock = if (notes.isEmpty()) {
            ""
        } else {
            eol + notes.joinToString(eol) { note -> "  $note" }
        }
        return subtree.substring(0, insertAt) + lead + ":LOGBOOK:" + eol + line +
            notesBlock + eol + ":END:" + eol + subtree.substring(insertAt)
    }

    /**
     * Where a NEW LOGBOOK drawer belongs: after the property drawer when one
     * exists, else after the planning block that directly follows the
     * headline, else directly after the headline line.
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

    /** Own-region WAIT state-log count — the one-transition write guard. */
    fun countWaitStateLogs(subtree: String): Int =
        WAIT_STATE_LOG_REGEX.findAll(subtree).count()

    /** Any `- State "KW" from "KW" […]` line — edit must preserve them all. */
    fun countStateLogs(subtree: String): Int =
        ANY_STATE_LOG_REGEX.findAll(subtree).count()

    /** CLOCK lines — an edit must never add or drop one. */
    fun countClocks(subtree: String): Int =
        CLOCK_LINE_REGEX.findAll(subtree).count()

    /**
     * org-log-note-headings 'state `"State %-12s from %-12S %t"` with the
     * "- " list prefix and the ` \\` note marker — the exact shape
     * [com.orgutil.domain.agenda.OrgAgendaParser] reads back. A null
     * from-state logs as the empty keyword, never the string "null".
     */
    private fun stateLogLine(
        toKeyword: String,
        fromKeyword: String?,
        stamp: String,
        hasNote: Boolean
    ): String {
        val from = "\"${fromKeyword.orEmpty()}\"".padEnd(12)
        return "- State " + "\"$toKeyword\"".padEnd(12) + " from " + from + " [$stamp]" +
            if (hasNote) " \\\\" else ""
    }

    private val STAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd EEE HH:mm", Locale.US)

    private val DRAWER_FENCE_REGEX = Regex("""^:(END|LOGBOOK|PROPERTIES):$""")
    private val STRUCTURAL_PREFIX_REGEX = Regex(
        """^(?:-[ \t]+(?:State[ \t]+"|CLOSING NOTE|Note taken)|CLOSED:|SCHEDULED:|DEADLINE:|CLOCK:)"""
    )

    // All patterns below mirror OrgAgendaParser/OrgCompletionLog so the
    // edited regions are exactly the regions the parser reads back.
    private val WAIT_STATE_LOG_REGEX = Regex(
        """(?m)^([ \t]*)-[ \t]+State[ \t]+"WAIT"[ \t]+from[ \t]+"([^"]*)"[ \t]*\[([^\]]*)\][ \t]*(\\\\)?[ \t]*\r?$"""
    )

    private val ANY_STATE_LOG_REGEX = Regex(
        """(?m)^[ \t]*-[ \t]+State[ \t]+"[^"]*"[ \t]+from"""
    )

    private val CLOCK_LINE_REGEX = Regex("""(?m)^[ \t]*CLOCK:""")

    private val PROPERTIES_DRAWER_REGEX = Regex(
        """(?ms)^[ \t]*:PROPERTIES:[ \t]*\r?\n(.*?)[ \t]*:END:"""
    )

    /** A SCHEDULED/DEADLINE/CLOSED planning line (the block boundary). */
    private val PLANNING_BLOCK_LINE_REGEX = Regex(
        """^[ \t]*(?:SCHEDULED|DEADLINE):[ \t]*<|^[ \t]*CLOSED:[ \t]*\["""
    )

    private val LOGBOOK_LINE_REGEX = Regex("""(?m)^([ \t]*):LOGBOOK:[ \t]*\r?$""")
}
