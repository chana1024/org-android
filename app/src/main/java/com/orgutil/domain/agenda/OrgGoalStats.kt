package com.orgutil.domain.agenda

import android.net.Uri
import java.time.LocalDate

/**
 * Shared 今日总目标 (today-goal) statistics — the same snapshot feeds the
 * Agenda stats card and the agenda widget's header, from ONE vault scan. The
 * UI layers only READ these fields; no arithmetic is duplicated there.
 *
 * Basis (kept deliberately explicit so app and widget can never drift):
 *  - Population is the UNION of the day's active goal groups — Today (planned
 *    today) + Next actions (unplanned NEXT) + Vibing + Waiting (unplanned
 *    WAIT) — as actual DISTINCT `(uri, sourceOffset)` headings, NOT the whole
 *    scanned vault. A planned-today WAIT is a Today member (deduped with the
 *    Waiting group by identity); HOLD/MAYBE/… count only through independent
 *    Today membership (planned today); SANDBAGGING never counts; structural
 *    PROJ/AREA and plain (no-keyword) headings never count even when planned
 *    today.
 *  - Ordinary tasks COMPLETED TODAY stay in both numerator and denominator
 *    after keyword DONE removes them from the active groups. The completion
 *    date is the heading's OWN evidence ([OrgAgendaEntry.ownCompletionDate]:
 *    CLOSED stamp / State-"DONE" log — never the scheduled date), and the
 *    item must still be provably eligible under the SAME union rule that
 *    governed it before completion: a state log dated today with from-state
 *    VIBING, NEXT or WAIT (unplanned or planned-today), or a Today-member
 *    state planned today; a CLOSED-today event without usable state-log
 *    evidence falls back to today planning only. Old unarchived DONE
 *    headings from previous days never inflate the totals.
 *  - HABITS are integrated in the SAME total — one identity per habit, no
 *    separate H/current-cycle metric. A habit is included when due today
 *    (Org due semantics: scheduled today or overdue) or completed today
 *    (a log dated today); rest-cycle habits are excluded even when habitual
 *    NEXT/VIBING membership would pull them in, and yesterday's completion
 *    on a rest cycle is NOT "completed today". Duplicate same-day logs count
 *    once; future-dated logs are ignored.
 *  - Archived headings (own `:ARCHIVE:` tag or any descendant of one; org
 *    -archive-set-tag semantics) are excluded — archive files are never
 *    scanned. CANCELLED/DROPPED are terminal cancellations, never
 *    completions: they stay out of BOTH numerator and denominator so
 *    dropping a task can never fake progress.
 *  - taskDone/taskTotal therefore answer "of today's goal population, how
 *    much is done TODAY": completing an active NEXT raises progress instead
 *    of making it vanish, and reopening an item makes it active again (a
 *    done heading keeps counting only while its keyword stays DONE).
 */
data class OrgGoalStats(
    /** Distinct headings in today's goal population (ordinary active + ordinary done-today + included habits). */
    val taskTotal: Int,
    /** Of those: completed TODAY (ordinary DONE-today evidence + habits logged done today). */
    val taskDone: Int
) {
    val progressFraction: Float
        get() = if (taskTotal <= 0) 0f else taskDone.toFloat() / taskTotal

    val progressPercent: Int
        get() = (progressFraction * 100).toInt()

    companion object {
        val EMPTY = OrgGoalStats(taskTotal = 0, taskDone = 0)
    }
}

/**
 * Computes [OrgGoalStats] from the parser's root entries. One recursive
 * walk: identity dedupe is (uri, sourceOffset) — overlapping memberships
 * (Today ∩ Next actions ∩ Vibing, habit rules on top) are resolved per
 * heading inside the walk, so a heading shown in several sections still
 * counts exactly once.
 */
object OrgGoalStatsCalculator {

    /** Org's default archive tag (org-archive-tag). */
    private const val ARCHIVE_TAG = "ARCHIVE"

    /**
     * Ordinary keywords that make a heading eligible through TODAY planning:
     * the Doom sequence's active keywords minus the structural PROJ/AREA,
     * minus SANDBAGGING (excluded from the day agenda), minus VIBING (which
     * enters through its own group instead). Plain no-keyword headings are
     * not eligible at all.
     */
    private val TODAY_MEMBER_KEYWORDS: Set<String> =
        (OrgAgendaParser.NOT_DONE_KEYWORDS.toSet() -
            setOf("PROJ", "AREA", "SANDBAGGING", "VIBING"))

    fun compute(roots: List<OrgAgendaEntry>, today: LocalDate): OrgGoalStats {
        // Identity is the ACTUAL (uri, sourceOffset) pair — never a collapsed
        // hash, which could collide across files/headings and silently
        // under-count against the DISTINCT guarantee.
        val seen = HashSet<Pair<Uri, Int>>()
        var taskTotal = 0
        var taskDone = 0

        fun visit(entry: OrgAgendaEntry, archivedAncestor: Boolean) {
            val archived = archivedAncestor || ARCHIVE_TAG in entry.tags
            if (!archived && seen.add(entry.uri to entry.sourceOffset)) {
                val habit = entry.habit
                val done = when {
                    // Habits: ONE identity per heading, habit rules override
                    // ordinary group membership entirely.
                    habit != null -> visitHabit(entry, habit, today)
                    // Ordinary headings: active T/N/V membership or retained
                    // done-today membership.
                    else -> visitOrdinary(entry, today)
                }
                if (done != null) {
                    taskTotal++
                    if (done) taskDone++
                }
            }
            entry.children.forEach { visit(it, archived) }
        }
        roots.forEach { visit(it, archivedAncestor = false) }

        return OrgGoalStats(taskTotal = taskTotal, taskDone = taskDone)
    }

    /**
     * Habit membership: included when due today (the SAME established Org due
     * semantics the Today section uses — scheduled today or overdue) OR
     * actually completed today; `true` when completed today, `false` when
     * merely included, `null` when excluded.
     *
     *  - Daily habits (srDays == 1) are included every ACTIVE day: a habit
     *    whose SCHEDULED is still in the future is pre-start and stays out.
     *  - Weekly/monthly habits (srDays > 1) additionally honor the rest
     *    cycle: scheduled in the future means not due, and habitual
     *    NEXT/VIBING membership must NOT pull the habit in anyway.
     *  - `today in doneDates` is pure recorded evidence: duplicate same-day
     *    logs collapse (one identity), future-dated logs can never satisfy
     *    it, and yesterday's completion on a rest cycle is not today's.
     *  - A cancelled/dropped habit is dead — never counted.
     */
    private fun visitHabit(entry: OrgAgendaEntry, habit: OrgHabit, today: LocalDate): Boolean? {
        if (entry.todo == "CANCELLED" || entry.todo == "DROPPED") return null
        val doneToday = today in habit.doneDates
        // Org due semantics (OrgAgendaBuilder.isHabitDue): the repeater keeps
        // a habit due from its scheduled day until done — overdue counts.
        val dueToday = !habit.scheduled.isAfter(today)
        return if (dueToday || doneToday) doneToday else null
    }

    /**
     * Ordinary membership: `true` when completed today (retained), `false`
     * when an active member of the T/N/V union, `null` when excluded.
     */
    private fun visitOrdinary(entry: OrgAgendaEntry, today: LocalDate): Boolean? {
        val todo = entry.todo ?: return null // plain headings never count
        if (todo == "PROJ" || todo == "AREA" ||
            todo == "CANCELLED" || todo == "DROPPED"
        ) return null
        if (todo != "DONE") {
            // Active union: Today (planned today, non-excluded states) ∪
            // Next actions (unplanned NEXT) ∪ Vibing ∪ Waiting (unplanned
            // WAIT) — the very filters the builder's daily sections use,
            // minus the habit handling.
            val todayMember = entry.hasPlanningOn(today) && todo in TODAY_MEMBER_KEYWORDS
            val nextActionMember = todo == "NEXT" && !entry.isPlanned
            val vibingMember = todo == "VIBING"
            val waitingMember = todo == "WAIT" && !entry.isPlanned
            return if (todayMember || nextActionMember || vibingMember || waitingMember) {
                false
            } else null
        }
        // Keyword DONE: retained ONLY with own dated completion evidence for
        // TODAY (never the scheduled date) AND provable prior eligibility —
        // historic DONE, undated DONE and unrelated today-completions stay
        // out, while a reopened item (active keyword again) is handled above
        // as the active case, so an old log can never keep it "done".
        if (entry.ownCompletionDate != today) return null
        // From-state evidence is valid ONLY when the latest State-"DONE" log
        // is itself dated TODAY: a stale older log belongs to a PREVIOUS
        // completion cycle and must never supply eligibility to a newer
        // CLOSED-today event whose prior state is unknown (that case falls
        // through to the honest legacy planning fallback below).
        val from = entry.stateDoneFrom.takeIf { entry.stateDoneDate == today }
        val eligible = when {
            // Vibing keeps planned and unplanned items — independently
            // eligible regardless of planning.
            from == "VIBING" -> true
            // Next-actions/Waiting membership requires !isPlanned, Today
            // membership planning-on-today: a NEXT or WAIT scheduled for a
            // FUTURE day was in none of the groups before its early
            // completion and stays out.
            from == "NEXT" || from == "WAIT" -> !entry.isPlanned || entry.hasPlanningOn(today)
            // TODO/HOLD/MAYBE … count only through independent Today
            // membership (planned today); SANDBAGGING/structural states never.
            from != null -> from in TODAY_MEMBER_KEYWORDS && entry.hasPlanningOn(today)
            // Honest legacy fallback: completion proven (CLOSED dated today,
            // or a state log without a parseable from-state) but the prior
            // state is unknowable — only the heading's OWN today planning can
            // still prove it was part of today's plan. Anything else cannot
            // be proven and is deliberately NOT counted.
            else -> entry.hasPlanningOn(today)
        }
        // Eligible DONE-today headings are COMPLETED (true); ineligible ones
        // are excluded outright (null) — an unprovable completion must never
        // dilute the denominator as a fake active member.
        return if (eligible) true else null
    }

    /** Any of the heading's own planning stamps falling on [date]. */
    private fun OrgAgendaEntry.hasPlanningOn(date: LocalDate): Boolean {
        return scheduled == date || deadline == date || timestamp == date
    }
}
