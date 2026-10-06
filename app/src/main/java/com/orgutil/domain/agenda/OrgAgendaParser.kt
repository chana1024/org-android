package com.orgutil.domain.agenda

import android.net.Uri
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OrgAgendaParser @Inject constructor() {

    fun parseFile(uri: Uri, fileName: String, content: String): List<OrgAgendaEntry> {
        val fileTags = parseFileTags(content)
        val flatEntries = parseFlatEntries(uri, fileName, content, fileTags)
        return buildTree(flatEntries)
    }

    private fun parseFlatEntries(
        uri: Uri,
        fileName: String,
        content: String,
        fileTags: Set<String>
    ): List<OrgAgendaEntry> {
        val matches = HEADLINE_REGEX.findAll(content).toList()
        return matches.mapIndexed { index, match ->
            val nextOffset = matches.getOrNull(index + 1)?.range?.first ?: content.length
            val subtree = content.substring(match.range.first, nextOffset)
            val stars = match.groupValues[1]
            val remainder = match.groupValues[2].trim()
            val parsed = parseHeadlineRemainder(remainder)
            val headlineLine = match.value
            val titleOffset = headlineLine.indexOf(parsed.title)
                .takeIf { it >= 0 }
                ?.let { match.range.first + it }
                ?: match.range.first
            // Own-region completion evidence for ordinary headings (the same
            // headline→next-heading slice every other own-field uses, so a
            // CHILD's CLOSED stamp or LOGBOOK can never leak in).
            val completion = parseOrdinaryCompletion(subtree)
            // The WAIT prompt note, exposed only while the heading itself is
            // WAIT — leaving WAIT always hides the summary (history stays in
            // the file); the note of a stale WAIT cycle never leaks out.
            val waitReason = if (parsed.todo == "WAIT") parseWaitReason(subtree) else null
            OrgAgendaEntry(
                uri = uri,
                fileName = fileName,
                level = stars.length,
                todo = parsed.todo,
                title = parsed.title,
                priority = parsed.priority,
                tags = fileTags + parsed.tags,
                scheduled = parsePlanningDate(subtree, "SCHEDULED"),
                deadline = parsePlanningDate(subtree, "DEADLINE"),
                scheduledTime = parsePlanningTime(subtree, "SCHEDULED"),
                deadlineTime = parsePlanningTime(subtree, "DEADLINE"),
                timestamp = parsePlainTimestampDate(subtree),
                habit = parseHabit(subtree),
                closedDate = completion?.closedDate,
                stateDoneDate = completion?.stateDoneDate,
                stateDoneFrom = completion?.stateDoneFrom,
                waitReason = waitReason,
                sourceOffset = match.range.first,
                titleOffset = titleOffset
            )
        }
    }

    private fun buildTree(flatEntries: List<OrgAgendaEntry>): List<OrgAgendaEntry> {
        if (flatEntries.isEmpty()) return emptyList()
        val result = mutableListOf<MutableAgendaEntry>()
        val stack = mutableListOf<MutableAgendaEntry>()

        flatEntries.forEach { entry ->
            val mutable = MutableAgendaEntry(entry)
            while (stack.isNotEmpty() && stack.last().entry.level >= entry.level) {
                stack.removeAt(stack.lastIndex)
            }
            if (stack.isEmpty()) {
                result += mutable
            } else {
                stack.last().children += mutable
            }
            stack += mutable
        }

        return result.map { it.toEntry() }
    }

    private fun parseHeadlineRemainder(remainder: String): ParsedHeadline {
        val rawTags = TAGS_REGEX.find(remainder)?.groupValues?.get(1).orEmpty()
        val tags = rawTags.split(":").filter { it.isNotBlank() }.toSet()
        val withoutTags = if (rawTags.isNotBlank()) {
            remainder.removeSuffix(":$rawTags:").trimEnd()
        } else {
            remainder
        }

        var remaining = withoutTags
        val todo = remaining.substringBefore(" ").takeIf { it in TODO_KEYWORDS }
        if (todo != null) {
            remaining = remaining.removePrefix(todo).trimStart()
        }

        val priority = PRIORITY_REGEX.find(remaining)?.groupValues?.get(1)
        if (priority != null) {
            remaining = remaining.replaceFirst(PRIORITY_REGEX, "").trimStart()
        }

        return ParsedHeadline(
            todo = todo,
            priority = priority,
            title = remaining,
            tags = tags
        )
    }

    private fun parseFileTags(content: String): Set<String> {
        return FILETAGS_REGEX.find(content)
            ?.groupValues
            ?.get(1)
            ?.split(":")
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()
    }

    private fun parsePlanningDate(subtree: String, keyword: String): LocalDate? {
        val regex = Regex("""$keyword:\s*<(\d{4}-\d{2}-\d{2})[^>]*>""")
        val date = regex.find(subtree)?.groupValues?.get(1) ?: return null
        return LocalDate.parse(date, DATE_FORMAT)
    }

    /**
     * Time-of-day of the keyword's own stamp, when it carries one
     * (`<2026-10-03 Sat 08:00 …>`); date-only stamps and bodies whose next
     * token is a repeater (`.+1w`) read as null.
     */
    private fun parsePlanningTime(subtree: String, keyword: String): LocalTime? {
        val regex = Regex(
            """$keyword:\s*<\d{4}-\d{2}-\d{2}(?:[ \t]+[A-Za-z]{3})?[ \t]+([0-2]?\d:[0-5]\d)[^>]*>"""
        )
        val time = regex.find(subtree)?.groupValues?.get(1) ?: return null
        return runCatching { LocalTime.parse(time, TIME_FORMAT) }.getOrNull()
    }

    private fun parsePlainTimestampDate(subtree: String): LocalDate? {
        return ACTIVE_TIMESTAMP_REGEX.findAll(subtree)
            .firstOrNull { match -> !isPlanningTimestamp(subtree, match.range.first) }
            ?.groupValues
            ?.get(1)
            ?.let { LocalDate.parse(it, DATE_FORMAT) }
    }

    private fun isPlanningTimestamp(subtree: String, timestampStart: Int): Boolean {
        val lineStart = subtree.lastIndexOf('\n', startIndex = timestampStart).let { index ->
            if (index == -1) 0 else index + 1
        }
        val prefix = subtree.substring(lineStart, timestampStart).trimEnd()
        return prefix.endsWith("SCHEDULED:") || prefix.endsWith("DEADLINE:")
    }

    /**
     * Recognizes an Org habit (org-habit.el org-habit-parse-todo): the
     * heading's OWN drawer must carry STYLE=habit and the OWN SCHEDULED
     * timestamp a valid repeater (+ / ++ / .+ with d/w/m/y, optional "/dr"
     * deadline window). The subtree stops at the next heading of any level,
     * so child properties/history can never leak in. Anything invalid
     * returns null — the entry keeps plain non-habit semantics and no graph.
     */
    private fun parseHabit(subtree: String): OrgHabit? {
        val properties = parseOwnPropertyDrawer(subtree)
        if (!properties["STYLE"].orEmpty().trim().equals("habit", ignoreCase = true)) return null

        val scheduledMatch = SCHEDULED_LINE_REGEX.find(subtree) ?: return null
        val scheduled = runCatching {
            LocalDate.parse(scheduledMatch.groupValues[1], DATE_FORMAT)
        }.getOrNull() ?: return null

        val repeater = REPEATER_REGEX.find(scheduledMatch.value) ?: return null
        val srDays = durationToDays(repeater.groupValues[2], repeater.groupValues[3])
        if (srDays <= 0) return null
        val drDays = repeater.groupValues[4].takeIf { it.isNotBlank() }
            ?.let { durationToDays(it, repeater.groupValues[5]) }
        if (drDays != null && drDays <= srDays) return null

        // Completion history: logged transitions into a DONE keyword
        // (org-log-into-drawer + org-log-done='time); LAST_REPEAT is the
        // deduplicated latest-date fallback when no log lines exist.
        val doneDates = parseDoneDates(subtree).ifEmpty {
            properties["LAST_REPEAT"]
                ?.let { timestampDate(it) }
                ?.let { listOf(it) }
                .orEmpty()
        }

        return OrgHabit(
            scheduled = scheduled,
            srDays = srDays,
            deadline = drDays?.let { scheduled.plusDays((it - srDays).toLong()) },
            drDays = drDays,
            doneDates = doneDates,
            srType = repeater.groupValues[1]
        )
    }

    /** First :PROPERTIES: drawer of the subtree, as a key/value map. */
    private fun parseOwnPropertyDrawer(subtree: String): Map<String, String> {
        val drawer = PROPERTIES_DRAWER_REGEX.find(subtree) ?: return emptyMap()
        return PROPERTY_LINE_REGEX.findAll(drawer.value).associate { match ->
            match.groupValues[1].uppercase(Locale.US) to match.groupValues[2].trim()
        }
    }

    /** Dates this habit was closed, from State "DONE"-style and CLOSING NOTE log lines. */
    private fun parseDoneDates(subtree: String): List<LocalDate> {
        return (DONE_STATE_LOG_REGEX.findAll(subtree) + CLOSING_NOTE_LOG_REGEX.findAll(subtree))
            .mapNotNull { match ->
                runCatching { LocalDate.parse(match.groupValues[1], DATE_FORMAT) }.getOrNull()
            }
            .distinct()
            .sorted()
            .toList()
    }

    /** Date part of an active/inactive timestamp body like "2025-09-24 Wed 21:04". */
    private fun timestampDate(timestampBody: String): LocalDate? {
        val date = TIMESTAMP_DATE_REGEX.find(timestampBody)?.groupValues?.get(1) ?: return null
        return runCatching { LocalDate.parse(date, DATE_FORMAT) }.getOrNull()
    }

    /**
     * Ordinary (non-habit) completion evidence from the heading's OWN region:
     * the `CLOSED: [date …]` planning stamp and the LATEST
     * `- State "DONE" from "…" [date …]` log line. The state log must be the
     * DONE keyword specifically — CANCELLED/DROPPED transitions are recorded
     * in the same drawer format but are never successful completions. Both
     * regexes scan only the own slice handed in, never a child subtree.
     */
    private fun parseOrdinaryCompletion(subtree: String): OrdinaryCompletion? {
        val closedDate = CLOSED_PLANNING_REGEX.find(subtree)
            ?.groupValues?.get(1)
            ?.let { runCatching { LocalDate.parse(it, DATE_FORMAT) }.getOrNull() }
        val stateDone = DONE_FROM_STATE_LOG_REGEX.findAll(subtree)
            // Latest by DATE, never by file order — new logs are inserted at
            // the TOP of the LOGBOOK, and a hand-edited drawer can be in any
            // order (ISO dates sort chronologically as strings).
            .mapNotNull { match ->
                runCatching { LocalDate.parse(match.groupValues[2], DATE_FORMAT) }.getOrNull()
                    ?.let { it to match.groupValues[1].trim() }
            }
            .maxByOrNull { it.first }
        if (closedDate == null && stateDone == null) return null
        return OrdinaryCompletion(
            closedDate = closedDate,
            stateDoneDate = stateDone?.first,
            stateDoneFrom = stateDone?.second
        )
    }

    private data class OrdinaryCompletion(
        val closedDate: LocalDate?,
        val stateDoneDate: LocalDate?,
        val stateDoneFrom: String?
    )

    /**
     * The note of the LATEST own `- State "WAIT"       from "…"       [ts] \\`
     * log line — what Doom's `WAIT(w@)` prompt wrote via org-store-log-note:
     * the ` \\` marker sits on the STATE line only, and every note line is
     * indented past it (org-list-item-body-column = 2 for a column-0 line).
     *
     * Latest is decided by the full `[ts]` body (lexical on the fixed-width
     * `yyyy-MM-dd …` stamp), never by document order — the vault's drawers
     * are newest-first but hand edits can reorder them. A latest WAIT
     * transition WITHOUT a marker has no note, and an older cycle's note is
     * never resurrected for it. Note collection stops at the first line
     * indented no deeper than the state line (the next log/CLOCK/:END:/
     * body line), at a bare `:END:` at any indent, or at EOF — so it can
     * never swallow the rest of the drawer or the descendant body.
     */
    private fun parseWaitReason(subtree: String): String? {
        val latest = WAIT_STATE_LOG_REGEX.findAll(subtree)
            .filter { it.groupValues[3].isNotBlank() }
            .maxByOrNull { it.groupValues[3] }
            ?: return null
        // No ` \\` marker: org-store-log-note appends it only when note
        // lines exist, so this transition was stored without a note.
        if (latest.groupValues[4].isEmpty()) return null

        val stateIndent = latest.groupValues[1].length
        val noteLines = mutableListOf<String>()
        // range.last is the last VISIBLE char (or the \r of a CRLF pair) —
        // skip past the line's newline to the first potential note line.
        var index = subtree.indexOf('\n', startIndex = latest.range.last)
            .let { if (it == -1) subtree.length else it + 1 }
        while (index < subtree.length) {
            val lineEnd = subtree.indexOf('\n', index).let {
                if (it == -1) subtree.length else it
            }
            val line = subtree.substring(index, lineEnd).trimEnd('\r')
            val indent = line.takeWhile { it == ' ' || it == '\t' }.length
            if (indent <= stateIndent || line.trim() == ":END:") break
            noteLines += line.trimStart()
            index = lineEnd + 1
        }
        return noteLines.filter { it.isNotBlank() }.joinToString("\n").trim()
            .takeIf { it.isNotEmpty() }
    }

    /** org-habit-duration-to-days: d=1 w=7 m=30.4 y=365.25, floored. */
    private fun durationToDays(count: String, unit: String): Int {
        val factor = when (unit) {
            "d" -> 1.0
            "w" -> 7.0
            "m" -> 30.4
            else -> 365.25
        }
        return Math.floor(count.toDouble() * factor).toInt()
    }

    private data class ParsedHeadline(
        val todo: String?,
        val priority: String?,
        val title: String,
        val tags: Set<String>
    )

    private class MutableAgendaEntry(
        val entry: OrgAgendaEntry,
        val children: MutableList<MutableAgendaEntry> = mutableListOf()
    ) {
        fun toEntry(parentTitles: List<String> = emptyList()): OrgAgendaEntry {
            return entry.copy(
                parentTitles = parentTitles,
                children = children.map { it.toEntry(parentTitles + entry.title) }
            )
        }
    }

    companion object {
        // Mirrors the Doom org-todo-keywords sequence: TODO NEXT VIBING
        // SANDBAGGING WAIT HOLD PROJ AREA MAYBE | DONE CANCELLED DROPPED.
        // Single source of truth: the agenda TODO editor picks from this
        // ordered list instead of keeping a divergent copy.
        val TODO_KEYWORDS_ORDERED = listOf(
            "TODO",
            "NEXT",
            "VIBING",
            "SANDBAGGING",
            "WAIT",
            "HOLD",
            "PROJ",
            "AREA",
            "MAYBE",
            "DONE",
            "CANCELLED",
            "DROPPED"
        )

        private val TODO_KEYWORDS = TODO_KEYWORDS_ORDERED.toSet()

        /** Everything before "|" in the Doom TODO sequence — Org's not-done keywords. */
        val NOT_DONE_KEYWORDS = TODO_KEYWORDS_ORDERED.takeWhile { it != "DONE" }

        /** Everything after "|" in the Doom TODO sequence — Org's done keywords. */
        val DONE_KEYWORDS = TODO_KEYWORDS_ORDERED
            .drop(TODO_KEYWORDS_ORDERED.indexOf("DONE"))
            .toSet()

        private val HEADLINE_REGEX = Regex("""(?m)^(\*+)\s+(.+)$""")
        private val FILETAGS_REGEX = Regex("""(?im)^#\+FILETAGS:\s*(.+)$""")
        private val TAGS_REGEX = Regex("""\s+:([A-Za-z0-9_@#%:.-]+):$""")
        private val PRIORITY_REGEX = Regex("""\[#([A-Z])]\s*""")
        private val ACTIVE_TIMESTAMP_REGEX = Regex("""<(\d{4}-\d{2}-\d{2})[^>]*>""")
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("H:mm", Locale.US)

        /** Own SCHEDULED planning line (timestamp body captured for the repeater). */
        private val SCHEDULED_LINE_REGEX = Regex("""(?m)^[ \t]*SCHEDULED:[ \t]*<(\d{4}-\d{2}-\d{2})[^>]*>""")

        /** Repeater inside a SCHEDULED timestamp: ".+1w", "++1m", "+1d/3d", ... */
        private val REPEATER_REGEX = Regex("""(\.\+|\+\+|\+)(\d+)([dwmy])(?:\s*/\s*(\d+)([dwmy]))?""")

        private val PROPERTIES_DRAWER_REGEX = Regex(
            """(?ms)^[ \t]*:PROPERTIES:[ \t]*\r?\n(.*?)[ \t]*:END:"""
        )
        private val PROPERTY_LINE_REGEX = Regex("""(?m)^[ \t]*:([A-Za-z0-9_-]+):[ \t]*(.*)$""")

        /** "- State \"DONE\" from \"TODO\" [2025-09-24 Wed 21:04]" log lines.
         *  The keyword alternation MUST stay inside a non-capturing group:
         *  ungrouped, `State "DONE|CANCELLED|DROPPED"` lets the regex match
         *  the bare prefix `- State "DONE` (the date group never
         *  participates), and parseDoneDates' runCatching drops every such
         *  match — silently losing ALL State-log completion dates and
         *  leaving LAST_REPEAT's single date as the only history. That
         *  starves the consistency graph's done dots AND misclassifies
         *  actually-completed days as missed. */
        private val DONE_STATE_LOG_REGEX = Regex(
            """(?m)^[ \t]*-[ \t]+State "(?:${DONE_KEYWORDS.joinToString("|") { Regex.escape(it) }})".*?\[(\d{4}-\d{2}-\d{2})[^\]]*\]"""
        )

        /** "- CLOSING NOTE [2025-09-24 Wed 21:04]" (org-log-done='time heading). */
        private val CLOSING_NOTE_LOG_REGEX = Regex(
            """(?m)^[ \t]*-[ \t]+CLOSING NOTE[ \t]*\[(\d{4}-\d{2}-\d{2})[^\]]*\]"""
        )

        /**
         * Own `CLOSED: [2026-10-05 Sun 10:12]` planning stamp — Org's standard
         * done-state timestamp. Matches the stamp whether it stands on its own
         * planning line or is merged first on a SCHEDULED/DEADLINE line
         * (`CLOSED: […] SCHEDULED: <…>`), and never a prose mention.
         */
        private val CLOSED_PLANNING_REGEX = Regex(
            """(?m)^[ \t]*CLOSED:[ \t]*\[(\d{4}-\d{2}-\d{2})[^\]]*\]"""
        )

        /**
         * `- State "DONE"       from "NEXT"       [2026-10-05 Sun 10:12]` —
         * the DONE-keyword state log WITH its from-state captured, for the
         * ordinary completion date and prior-eligibility evidence. Anchored
         * to the log-line shape (list dash + quoted keywords + timestamp),
         * so a CANCELLED/DROPPED log line never matches and prose quoting a
         * log line cannot satisfy the tail.
         */
        private val DONE_FROM_STATE_LOG_REGEX = Regex(
            """(?m)^[ \t]*-[ \t]+State[ \t]+"DONE"[ \t]+from[ \t]+"([^"]*)"[ \t]*\[\s*(\d{4}-\d{2}-\d{2})[^\]]*\]"""
        )

        /**
         * `- State "WAIT"       from "NEXT"       [2026-09-23 Wed 10:42] \\` —
         * the WAIT-keyword state log WITH its optional note marker captured.
         * Groups: 1 = state-line indent (hand-indented drawers), 2 = from
         * keyword, 3 = the full `[…]` stamp body (latest-selection key),
         * 4 = the ` \\` note marker, present only when org stored note lines.
         * `\r?` before the `$` keeps the line anchored on CRLF files. WAIT is
         * not a done keyword, so this can never overlap the DONE-evidence
         * regexes above.
         */
        private val WAIT_STATE_LOG_REGEX = Regex(
            """(?m)^([ \t]*)-[ \t]+State[ \t]+"WAIT"[ \t]+from[ \t]+"([^"]*)"[ \t]*\[([^\]]*)\][ \t]*(\\\\)?[ \t]*\r?$"""
        )

        private val TIMESTAMP_DATE_REGEX = Regex("""(\d{4}-\d{2}-\d{2})""")
    }
}
