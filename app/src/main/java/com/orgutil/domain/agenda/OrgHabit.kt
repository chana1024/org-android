package com.orgutil.domain.agenda

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Semantic proximity of a real DEADLINE date relative to today. */
enum class HabitDeadlineProximity { UPCOMING, TODAY, OVERDUE }

/**
 * Calendar-day countdown to a heading's REAL `DEADLINE:` planning stamp.
 *
 * Deliberately computed from [org.agenda.OrgAgendaEntry.deadline] (the parsed
 * `DEADLINE:` line), NOT from `OrgHabit.deadline` — that field is the
 * repeater's "/Nd" graph warning window (scheduled + dr - sr) and must never
 * be labelled as an actual deadline. Whole calendar days in the device's
 * zone: a DEADLINE carrying a time-of-day still counts down by date.
 */
data class HabitDeadlineCountdown(
    val days: Long,
    val proximity: HabitDeadlineProximity,
    /** 距截止 N 天 / 今天截止 / 已逾期 N 天 */
    val label: String
)

fun habitDeadlineCountdown(deadline: LocalDate, today: LocalDate): HabitDeadlineCountdown {
    val days = ChronoUnit.DAYS.between(today, deadline)
    return when {
        days > 0 -> HabitDeadlineCountdown(days, HabitDeadlineProximity.UPCOMING, "距截止 $days 天")
        days == 0L -> HabitDeadlineCountdown(0L, HabitDeadlineProximity.TODAY, "今天截止")
        else -> HabitDeadlineCountdown(days, HabitDeadlineProximity.OVERDUE, "已逾期 ${-days} 天")
    }
}

/** Where a habit row's countdown cutoff date came from. */
enum class HabitCutoffSource {
    /** The heading's real `DEADLINE:` planning stamp — always wins. */
    DEADLINE,

    /**
     * The repeater's configured "/Nd" deadline window
     * ([OrgHabit.effectiveDeadline] = scheduled + dr − sr). Only genuine
     * windows count: no "/dr" part → no cutoff, never a fabricated date.
     */
    REPEATER_WINDOW
}

/**
 * Effective cutoff for a habit row's countdown: the REAL `DEADLINE:` stamp
 * when one exists, else the repeater's "/Nd" window end — the very same
 * [OrgHabit.effectiveDeadline] date the consistency graph's current cycle
 * uses as its ALERT day ([OrgHabit] faceFor), so chip and graph can never
 * disagree. Null when the heading has neither (no fabricated deadline).
 */
data class HabitCutoff(
    val date: LocalDate,
    val source: HabitCutoffSource,
    val countdown: HabitDeadlineCountdown
)

fun habitCutoff(
    explicitDeadline: LocalDate?,
    habit: OrgHabit,
    today: LocalDate
): HabitCutoff? {
    if (explicitDeadline != null) {
        return HabitCutoff(
            date = explicitDeadline,
            source = HabitCutoffSource.DEADLINE,
            countdown = habitDeadlineCountdown(explicitDeadline, today)
        )
    }
    // Window cutoff only for a genuine "/dr" part: OrgHabit.deadline is
    // derived from drDays by the parser, and effectiveDeadline resolves to
    // it — never the bare scheduled date (that is not a deadline).
    if (habit.drDays != null) {
        val windowEnd = habit.effectiveDeadline
        return HabitCutoff(
            date = windowEnd,
            source = HabitCutoffSource.REPEATER_WINDOW,
            countdown = habitDeadlineCountdown(windowEnd, today)
        )
    }
    return null
}

/**
 * Org habit model and consistency-graph algorithm, ported from the
 * user's local org-habit.el (org-habit-parse-todo, org-habit-get-faces,
 * org-habit-build-graph). An entry is a habit only when its own heading
 * carries STYLE=habit AND a valid repeating SCHEDULED timestamp — plain
 * repeating tasks are not habits.
 *
 * Repeater semantics (per org-habit.el):
 *  - "+"  schedule-anchored: each completion shifts scheduled by srDays.
 *  - "++" catch-up: scheduled jumps to the first multiple of srDays past done.
 *  - ".+" completion-anchored: scheduled = last done + srDays.
 * An optional deadline window "/Nd" extends the due period (must be > sr).
 */
data class OrgHabit(
    val scheduled: LocalDate,
    /** Scheduled repeat period in days (floor of d/w/m/y per org-habit-duration-to-days). */
    val srDays: Int,
    /** Explicit deadline date (scheduled + dr - sr) when a "/dr" window exists. */
    val deadline: LocalDate?,
    /** Deadline repeat period in days; null when no "/dr" part. */
    val drDays: Int?,
    /** Ascending unique dates the habit was logged DONE. */
    val doneDates: List<LocalDate>,
    /** "+", "++" or ".+". */
    val srType: String
) {
    /** org-habit-deadline: effective end of the due window. */
    val effectiveDeadline: LocalDate
        get() = deadline
            ?: if (drDays != null) scheduled.plusDays((srDays - 1).toLong()) else scheduled

    /** org-habit-deadline-repeat. */
    private val dRepeatDays: Int
        get() = drDays ?: srDays

    enum class Face { CLEAR, READY, ALERT, OVERDUE }

    /** One cell of the consistency graph. */
    data class Day(
        val date: LocalDate,
        val face: Face,
        /** Org draws past unmarked non-overdue days with the dim "future" variant. */
        val dimmed: Boolean,
        val done: Boolean,
        val isToday: Boolean,
        /**
         * SKIPPED repetition opportunity: a strictly-past day on which the
         * habit's cadence called for a completion (or, for a daily habit,
         * simply a day of its recorded active interval) and none was ever
         * recorded there. Deliberately INDEPENDENT of the Org face/deadline
         * window coloring — a past deadline-window expiring (ALERT/OVERDUE)
         * is NOT the same as a skipped repetition, and a long "/dr" window
         * must not hide daily skips:
         *  - sr == 1: every past undone day from the earliest PROVABLE
         *    activity anchor ([dailyActiveStart]) — window ignored, a skip
         *    is missed the next day.
         *  - sr > 1: only the cadence's repetition DUE DATES (the day equal
         *    to the effective scheduled date the +/++/.+ rewind reconstructs
         *    for that point in history) whose due window/slot holds no
         *    recorded completion. Rest days between due dates are never
         *    marked. A completion anywhere inside the due day's org window
         *    (D + dr − sr) or, without one, its slot until the next
         *    occurrence (D + sr − 1) neutralizes that due date (late
         *    completion ≠ skip).
         * Today, future days and done days are never missed. The anchor
         * uses only recorded history, so pruned logbooks can only
         * under-report misses, never fabricate them.
         */
        val missed: Boolean
    )

    /**
     * org-habit-build-graph over [today - 21, today + 7] (Doom defaults:
     * 21 preceding days, today, 7 following days — the graph is anchored to
     * the current day like org-habit-show-habits-only-for-today).
     */
    fun buildGraph(today: LocalDate): List<Day> {
        val start = today.minusDays(PRECEDING_DAYS.toLong())
        val end = today.plusDays(FOLLOWING_DAYS.toLong())
        val allDone = doneDates.sorted()
        // Earliest provable activity anchor for the daily (sr == 1) skipped-day
        // rule. Only recorded evidence counts: no completions -> the current
        // SCHEDULED (never advanced, i.e. the file's own claim); "+" ->
        // min(first done, scheduled − n·sr) (either proves the interval);
        // "++"/".+" re-anchor on completion, so nothing before the first
        // recorded done is provable. Truncated history can only push this
        // anchor LATER — under-reporting skips, never fabricating them.
        val dailyActiveStart = when {
            allDone.isEmpty() -> scheduled
            srType == "+" ->
                minOf(allDone.first(), scheduled.minusDays((allDone.size * srDays).toLong()))
            else -> allDone.first()
        }
        var doneDates = allDone
        var lastDoneDate: LocalDate? = null
        // org-habit skips done dates before the graph window but keeps the
        // latest one as the anchor for historical scheduling.
        while (doneDates.isNotEmpty() && doneDates.first() < start) {
            lastDoneDate = doneDates.first()
            doneDates = doneDates.drop(1)
        }

        val days = mutableListOf<Day>()
        var current = start
        while (current <= end) {
            val inThePast = current < today
            val isToday = current == today
            val done = doneDates.isNotEmpty() && current == doneDates.first()

            val face: Face
            val historicalScheduled: LocalDate?
            if (inThePast && lastDoneDate == null && !scheduled.isBefore(today)) {
                // Before any completion and not yet due: Org shows the very
                // first done as ready, everything else clear.
                historicalScheduled = null
                face = if (allDone.isNotEmpty() && allDone.first() == current) Face.READY else Face.CLEAR
            } else {
                historicalScheduled = if (inThePast && lastDoneDate != null) {
                    historicalScheduledAt(lastDoneDate, doneDates, allDone)
                } else {
                    null
                }
                face = faceFor(current, historicalScheduled, done)
            }

            if (done) {
                // Advance past any duplicates logged on this date.
                while (doneDates.isNotEmpty() && current == doneDates.first()) {
                    lastDoneDate = doneDates.first()
                    doneDates = doneDates.drop(1)
                }
            }

            // SKIPPED repetition opportunity — deliberately independent of
            // the face/deadline-window coloring: a window expiring is not a
            // skipped repetition, and a long "/dr" window must not hide
            // daily skips. doneDates now holds exactly the recorded
            // completions AFTER current (the done-day drop above already ran;
            // on a not-done day nothing was dropped), which is the search
            // space for "was this repetition completed, possibly late".
            val missed = inThePast && !done && when {
                // Daily: every skipped past day of the recorded active
                // interval — the org window is irrelevant here.
                srDays == 1 -> !current.isBefore(dailyActiveStart)
                else -> {
                    // Period habits: only the cadence's repetition DUE DATES
                    // (the day equal to the effective scheduled date the
                    // +/++/.+ rewind reconstructs for this point in history).
                    // Rest days between due dates are never marked.
                    val dueDate = historicalScheduled ?: scheduled
                    current == dueDate && run {
                        // Org "/dr" window when present, else the slot until
                        // the next occurrence: a completion recorded in there
                        // completes THIS repetition (late), not a skip.
                        val satEnd = current.plusDays(
                            ((drDays?.let { it - srDays } ?: (srDays - 1))).toLong()
                        )
                        doneDates.none { !it.isAfter(satEnd) }
                    }
                }
            }

            // Past/today use the bright face, future days the dim variant;
            // past unmarked non-overdue days are dimmed too (org-habit uses
            // the future face for those). Today is always bright.
            val overdue = face == Face.OVERDUE
            val dimmed = when {
                isToday -> false
                inThePast -> !done && !overdue
                else -> true
            }
            days += Day(
                date = current,
                face = face,
                dimmed = dimmed,
                done = done,
                isToday = isToday,
                missed = missed
            )
            current = current.plusDays(1)
        }
        return days
    }

    /**
     * org-habit-get-faces: color for day [m] relative to the (possibly
     * historical) scheduled date. scheduledDays null = current scheduling.
     */
    private fun faceFor(m: LocalDate, scheduledDays: LocalDate?, donep: Boolean): Face {
        val scheduled = scheduledDays ?: scheduled
        val deadline = scheduledDays?.plusDays((dRepeatDays - srDays).toLong())
            ?: effectiveDeadline
        return when {
            m < scheduled -> Face.CLEAR
            m < deadline -> Face.READY
            m == deadline -> if (donep) Face.READY else Face.ALERT
            else -> Face.OVERDUE
        }
    }

    /**
     * Scheduled date that was in effect back when [lastDone] was the most
     * recent completion (the big cond in org-habit-build-graph). Only called
     * for past days with a prior completion.
     */
    private fun historicalScheduledAt(
        lastDone: LocalDate,
        doneDatesRemaining: List<LocalDate>,
        allDone: List<LocalDate>
    ): LocalDate {
        if (doneDatesRemaining.isEmpty()) {
            // At/after the last done date: current scheduling in all cases.
            return scheduled
        }
        return when (srType) {
            ".+" -> lastDone.plusDays(srDays.toLong())
            "+" -> {
                // Each done mark since LAST-DONE shifted scheduled by srDays.
                scheduled.minusDays((doneDatesRemaining.size * srDays).toLong())
            }
            else -> {
                // "++": replay the done history from the first completion.
                val firstDone = allDone.first()
                val shift = Math.floorMod(scheduled.toEpochDay() - firstDone.toEpochDay(), srDays)
                var s = firstDone.plusDays(if (shift == 0) srDays.toLong() else shift.toLong())
                if (firstDone == lastDone) {
                    return s
                }
                for (done in allDone.drop(1)) {
                    // Each repeat shifts S by as many sr hops as needed to
                    // pass DONE, minimum one hop.
                    val hops = 1L + maxOf(done.toEpochDay() - s.toEpochDay(), 0L) / srDays
                    s = s.plusDays(hops * srDays)
                    if (done == lastDone) return s
                }
                s
            }
        }
    }

    companion object {
        // Local Doom/org-habit.el defaults: 21 preceding, 7 following.
        const val PRECEDING_DAYS = 21
        const val FOLLOWING_DAYS = 7
    }
}
