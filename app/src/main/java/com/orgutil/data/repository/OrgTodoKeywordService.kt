package com.orgutil.data.repository

import com.orgutil.domain.agenda.OrgAgendaEntry
import com.orgutil.domain.agenda.OrgAgendaParser
import com.orgutil.domain.model.OrgDocument
import com.orgutil.domain.repository.OrgFileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Surgical TODO-keyword editor for agenda entries. Only the keyword token on
 * the exact source heading is rewritten; the title, tags, priority, planning
 * lines, children, whitespace and every unrelated entry are preserved
 * verbatim. All writes go through [OrgFileRepository.writeOrgFile], which
 * SAF-writes, verifies by read-back and syncs the search index.
 *
 * Habits get Org's repeat semantics: marking one done (any keyword after the
 * "|" of the sequence) routes through [OrgHabitRepeat], which advances the
 * repeating SCHEDULED, logs the completion into LOGBOOK and resets the
 * heading to the repeat-to state — the same transition Doom Emacs makes.
 *
 * Ordinary (non-habit) headings switched to a done keyword additionally get
 * [OrgCompletionLog]'s standard bookkeeping — a CLOSED stamp plus a
 * State log line in the heading's own LOGBOOK — so the completion DATE and
 * the prior state live in the file and today's goal statistics can be
 * reconstructed from Org evidence alone after any refresh or restart.
 *
 * A transition into WAIT instead gets [OrgWaitNoteLog]'s explanatory note —
 * the `WAIT(w@)` prompt note Emacs records — and NEVER completion evidence:
 * no CLOSED stamp, no State-"DONE" log, so waiting cannot inflate the done
 * counts while the `from` keyword still records the prior state.
 */
@Singleton
class OrgTodoKeywordService @Inject constructor(
    private val orgFileRepository: OrgFileRepository,
    private val parser: OrgAgendaParser
) {

    /**
     * Sets or clears the TODO keyword of the heading backing [entry].
     *
     * For an eligible habit (STYLE=habit + repeating SCHEDULED, per the
     * parsed [OrgAgendaEntry.habit]) and a done keyword, the applied state
     * is the repeat-to state, not [newKeyword] itself.
     *
     * A transition into WAIT (heading not already WAIT) requires a
     * meaningful [waitReason]; it is recorded as the LOGBOOK state-note in
     * Emacs's exact format.
     *
     * @param newKeyword a keyword from [OrgAgendaParser.TODO_KEYWORDS_ORDERED],
     *   or null to strip the keyword entirely.
     * @param waitReason the explanatory note required when [newKeyword] is
     *   WAIT and the heading is not WAIT yet; ignored otherwise.
     * @throws IllegalStateException when the file changed since the agenda was
     *   loaded (offsets no longer match) so a wrong heading could be touched.
     */
    suspend fun setTodoKeyword(
        entry: OrgAgendaEntry,
        newKeyword: String?,
        waitReason: String? = null
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(newKeyword == null || newKeyword in OrgAgendaParser.TODO_KEYWORDS_ORDERED) {
                    "Unsupported TODO keyword: $newKeyword"
                }
                if (entry.todo == newKeyword) return@runCatching

                // WAIT is a note-taking keyword (w@): a fresh transition
                // without a meaningful reason is refused before any read.
                val isWaitTransition = newKeyword == "WAIT" && entry.todo != "WAIT"
                val waitReasonText = if (isWaitTransition) {
                    val reason = waitReason.orEmpty().trim()
                    check(reason.isNotEmpty()) { OrgWaitNoteLog.REASON_REQUIRED }
                    OrgWaitNoteLog.validateReason(reason)?.let { error(it) }
                    reason
                } else {
                    ""
                }

                val document = orgFileRepository.readOrgFile(entry.uri).getOrThrow()
                val content = document.content
                val region = resolveHeadingRegion(content, entry)

                // Habit completion (org-auto-repeat-maybe): a done keyword on
                // a habit never stays on the heading. Org logs the transition
                // and resets the heading to the repeat-to state, so that is
                // the token written below.
                val habitDoneKeyword = newKeyword?.takeIf { keyword ->
                    entry.habit != null && keyword in OrgAgendaParser.DONE_KEYWORDS
                }
                // ORDINARY completion bookkeeping (org-log-done / org-log-into
                // -drawer): a done keyword on a non-habit heading gets the
                // standard CLOSED stamp + State log, so WHEN it was completed
                // (and from which state) survives in the file itself — the
                // evidence today's goal statistics reconstruct from, not
                // ephemeral UI memory. Same region discipline as the habit
                // repeat: only the entry's own slice, heading line verbatim.
                val ordinaryDoneKeyword = newKeyword?.takeIf { keyword ->
                    entry.habit == null && keyword in OrgAgendaParser.DONE_KEYWORDS
                }
                var working = content
                var finalKeyword = newKeyword
                var expectedScheduled: LocalDate? = null
                var expectedStateDoneFrom: String? = null
                if (habitDoneKeyword != null) {
                    // The entry's own region: up to the next heading of any
                    // level, the same slice the parser reads habits from.
                    val subtreeEnd = nextHeadingStart(content, region.lineStart)
                    val repeat = OrgHabitRepeat.apply(
                        subtree = content.substring(region.lineStart, subtreeEnd),
                        doneKeyword = habitDoneKeyword,
                        fromKeyword = entry.todo,
                        today = LocalDate.now(),
                        now = LocalDateTime.now()
                    )
                    working = content.substring(0, region.lineStart) + repeat.subtree +
                        content.substring(subtreeEnd)
                    finalKeyword = repeat.finalKeyword
                    expectedScheduled = repeat.newScheduled
                } else if (ordinaryDoneKeyword != null) {
                    val subtreeEnd = nextHeadingStart(content, region.lineStart)
                    working = content.substring(0, region.lineStart) + OrgCompletionLog.apply(
                        subtree = content.substring(region.lineStart, subtreeEnd),
                        doneKeyword = ordinaryDoneKeyword,
                        fromKeyword = entry.todo,
                        now = LocalDateTime.now()
                    ) + content.substring(subtreeEnd)
                    expectedStateDoneFrom = entry.todo.orEmpty()
                } else if (isWaitTransition) {
                    val subtreeEnd = nextHeadingStart(content, region.lineStart)
                    working = content.substring(0, region.lineStart) + OrgWaitNoteLog.insertTransitionNote(
                        subtree = content.substring(region.lineStart, subtreeEnd),
                        fromKeyword = entry.todo,
                        reason = waitReasonText,
                        now = LocalDateTime.now()
                    ) + content.substring(subtreeEnd)
                }

                val newTokens = buildList {
                    add("*".repeat(entry.level))
                    if (finalKeyword != null) add(finalKeyword)
                    // Keep priority etc.; the old keyword token (if any) is
                    // replaced by finalKeyword above or simply removed.
                    region.tokens.drop(1).forEach { token ->
                        if (entry.todo == null || token != entry.todo) add(token)
                    }
                }
                val newPrefix = newTokens.joinToString(" ") + " "
                val newContent = working.substring(0, region.lineStart) + newPrefix +
                    working.substring(entry.titleOffset)

                // Semantic pre-write check: the edited heading must parse back
                // with the applied keyword before anything is persisted.
                // parseFile returns roots only, so flatten the whole tree to
                // reach nested headings.
                val reparsed = findReparsedEntry(newContent, entry, region.lineStart)
                check(reparsed?.todo == finalKeyword && reparsed?.title == entry.title) {
                    "编辑后的标题解析结果与预期不一致，已取消写入"
                }
                if (habitDoneKeyword != null) {
                    // The repeat must round-trip: still a habit, scheduled
                    // advanced as computed and today logged as a completion
                    // the habit graph can parse.
                    val habit = checkNotNull(reparsed?.habit) { REPEAT_MISMATCH }
                    check(
                        habit.scheduled == expectedScheduled &&
                            LocalDate.now() in habit.doneDates
                    ) { REPEAT_MISMATCH }
                }
                if (ordinaryDoneKeyword != null) {
                    // The completion bookkeeping must round-trip. CANCELLED/
                    // DROPPED get the same CLOSED stamp but are terminal
                    // cancellations — they must NEVER read back as successful
                    // DONE evidence (no State-"DONE" log exists for them).
                    if (ordinaryDoneKeyword == "DONE") {
                        check(
                            reparsed?.closedDate == LocalDate.now() &&
                                reparsed?.stateDoneDate == LocalDate.now() &&
                                reparsed?.stateDoneFrom == expectedStateDoneFrom
                        ) { COMPLETION_MISMATCH }
                    } else {
                        check(reparsed?.closedDate == LocalDate.now()) { COMPLETION_MISMATCH }
                    }
                }
                if (isWaitTransition) {
                    // The note must round-trip verbatim, the heading must
                    // carry EXACTLY ONE fresh WAIT state log, and no
                    // completion evidence may appear (WAIT never counts as
                    // done — CLOSED/DONE evidence must stay what it was).
                    checkWaitNoteInvariants(
                        oldSubtree = content.substring(
                            region.lineStart,
                            nextHeadingStart(content, region.lineStart)
                        ),
                        newContent = newContent,
                        lineStart = region.lineStart,
                        reparsed = reparsed,
                        expectedReason = OrgWaitNoteLog.normalizeReason(waitReasonText),
                        insertedFresh = true,
                        entry = entry
                    )
                }

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

    /**
     * Rewrites ONLY the note of the heading's latest own WAIT state log —
     * the keyword token, timestamp, from-state, every other log line and
     * all completion evidence stay byte-identical. A blank [newReason]
     * removes the note (empty-prompt transition shape); null/absent WAIT
     * log gets a fresh line. The heading must still BE WAIT at write time.
     */
    suspend fun editWaitReason(entry: OrgAgendaEntry, newReason: String?): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                check(entry.todo == "WAIT") { NOT_WAIT_ANYMORE }
                val reason = newReason.orEmpty().trim()
                if (reason.isNotEmpty()) {
                    OrgWaitNoteLog.validateReason(reason)?.let { error(it) }
                }

                val document = orgFileRepository.readOrgFile(entry.uri).getOrThrow()
                val content = document.content
                val region = resolveHeadingRegion(content, entry)
                // The heading must still carry WAIT at write time — a stale
                // agenda row must never attach a note to a moved-on heading.
                check(region.tokens.getOrNull(1) == "WAIT") { NOT_WAIT_ANYMORE }

                val subtreeEnd = nextHeadingStart(content, region.lineStart)
                val oldSubtree = content.substring(region.lineStart, subtreeEnd)
                val edit = OrgWaitNoteLog.editLatestNote(oldSubtree, reason, LocalDateTime.now())
                val newContent = content.substring(0, region.lineStart) + edit.subtree +
                    content.substring(subtreeEnd)

                val reparsed = findReparsedEntry(newContent, entry, region.lineStart)
                check(reparsed?.todo == "WAIT" && reparsed?.title == entry.title) {
                    "编辑后的标题解析结果与预期不一致，已取消写入"
                }
                checkWaitNoteInvariants(
                    oldSubtree = oldSubtree,
                    newContent = newContent,
                    lineStart = region.lineStart,
                    reparsed = reparsed,
                    expectedReason = if (reason.isEmpty()) {
                        null
                    } else {
                        OrgWaitNoteLog.normalizeReason(reason)
                    },
                    insertedFresh = edit.insertedFresh,
                    entry = entry
                )

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

    /**
     * Shared post-edit invariants of both WAIT-note writes: the heading's
     * own region parses back with exactly the expected reason, gained (at
     * most) the one intended WAIT state line, kept every CLOCK line and
     * every other state log, and — the statistics guard — kept its
     * completion evidence exactly as it was (no CLOSED/DONE forgery).
     */
    private fun checkWaitNoteInvariants(
        oldSubtree: String,
        newContent: String,
        lineStart: Int,
        reparsed: OrgAgendaEntry?,
        expectedReason: String?,
        insertedFresh: Boolean,
        entry: OrgAgendaEntry
    ) {
        val newSubtree = newContent.substring(lineStart, nextHeadingStart(newContent, lineStart))
        check(reparsed?.waitReason == expectedReason) { WAIT_NOTE_MISMATCH }
        check(
            OrgWaitNoteLog.countWaitStateLogs(newSubtree) ==
                OrgWaitNoteLog.countWaitStateLogs(oldSubtree) + if (insertedFresh) 1 else 0
        ) { WAIT_NOTE_MISMATCH }
        check(
            OrgWaitNoteLog.countStateLogs(newSubtree) ==
                OrgWaitNoteLog.countStateLogs(oldSubtree) + if (insertedFresh) 1 else 0
        ) { WAIT_NOTE_MISMATCH }
        check(OrgWaitNoteLog.countClocks(newSubtree) == OrgWaitNoteLog.countClocks(oldSubtree)) {
            WAIT_NOTE_MISMATCH
        }
        // Statistics guard: no completion evidence may move.
        check(
            reparsed?.closedDate == entry.closedDate &&
                reparsed?.stateDoneDate == entry.stateDoneDate &&
                reparsed?.stateDoneFrom == entry.stateDoneFrom
        ) { WAIT_NOTE_MISMATCH }
    }

    /**
     * Locates the entry's exact heading in freshly read [content] via the
     * offsets captured at parse time; region-matching the title guarantees
     * this is the selected heading, not a similarly named one. Returns the
     * line start, subtree end, prefix and its tokens for the caller's
     * surgical rewrite.
     */
    private fun resolveHeadingRegion(content: String, entry: OrgAgendaEntry): HeadingRegion {
        val lineStart = content.lastIndexOf('\n', startIndex = entry.sourceOffset)
            .let { if (it == -1) 0 else it + 1 }
        val stars = "*".repeat(entry.level)
        check(content.startsWith(stars, startIndex = lineStart)) { HEADING_MOVED }
        check(entry.titleOffset in lineStart until content.length) { HEADING_MOVED }
        check(
            entry.titleOffset + entry.title.length <= content.length &&
                content.regionMatches(entry.titleOffset, entry.title, 0, entry.title.length)
        ) { HEADING_MOVED }

        // Prefix = stars + optional keyword + optional priority, up to
        // the title start.
        val prefix = content.substring(lineStart, entry.titleOffset)
        val tokens = prefix.trim().split(WHITESPACE).filter { it.isNotBlank() }
        check(tokens.firstOrNull() == stars) { HEADING_MOVED }
        if (entry.todo != null) {
            check(tokens.getOrNull(1) == entry.todo) { HEADING_MOVED }
        }
        return HeadingRegion(
            lineStart = lineStart,
            subtreeEnd = nextHeadingStart(content, lineStart),
            tokens = tokens
        )
    }

    private class HeadingRegion(
        val lineStart: Int,
        val subtreeEnd: Int,
        val tokens: List<String>
    )

    /** Re-parses [newContent] and finds the entry's heading at [lineStart]. */
    private fun findReparsedEntry(
        newContent: String,
        entry: OrgAgendaEntry,
        lineStart: Int
    ): OrgAgendaEntry? =
        parser.parseFile(entry.uri, entry.fileName, newContent)
            .asSequence()
            .flatMap { it.selfAndDescendants() }
            .firstOrNull { it.sourceOffset == lineStart && it.level == entry.level }

    /** Start of the next heading of any level, or the end of the content. */
    private fun nextHeadingStart(content: String, from: Int): Int =
        NEXT_HEADING_REGEX.find(content, from + 1)?.range?.first ?: content.length

    private fun OrgAgendaEntry.selfAndDescendants(): Sequence<OrgAgendaEntry> =
        sequenceOf(this) + children.asSequence().flatMap { it.selfAndDescendants() }

    private companion object {
        const val HEADING_MOVED = "文件内容已变化，标题位置不匹配；请刷新 Agenda 后重试"
        const val REPEAT_MISMATCH = "习惯重复规则应用后解析结果与预期不一致，已取消写入"
        const val COMPLETION_MISMATCH = "完成记录写入后解析结果与预期不一致，已取消写入"
        const val WAIT_NOTE_MISMATCH = "等待原因写入后解析结果与预期不一致，已取消写入"
        const val NOT_WAIT_ANYMORE = "该标题当前不是 WAIT 状态；请刷新 Agenda 后重试"

        /** Mirrors OrgAgendaParser.HEADLINE_REGEX for the subtree boundary. */
        val NEXT_HEADING_REGEX = Regex("""(?m)^(\*+)\s+(.+)$""")
        val WHITESPACE = Regex("\\s+")
    }
}

