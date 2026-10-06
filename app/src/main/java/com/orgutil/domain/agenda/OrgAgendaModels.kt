package com.orgutil.domain.agenda

import android.net.Uri
import java.time.LocalDate
import java.time.LocalTime

data class OrgAgendaEntry(
    val uri: Uri,
    val fileName: String,
    val level: Int,
    val todo: String?,
    val title: String,
    val priority: String?,
    val tags: Set<String>,
    val scheduled: LocalDate?,
    val deadline: LocalDate?,
    /** Time-of-day of the own SCHEDULED stamp (`<… 08:00>`), null when date-only. */
    val scheduledTime: LocalTime? = null,
    /** Time-of-day of the own DEADLINE stamp (`<… 08:00>`), null when date-only. */
    val deadlineTime: LocalTime? = null,
    val timestamp: LocalDate? = null,
    /** Parsed STYLE=habit data (own drawer + own LOGBOOK only); null when not a valid habit. */
    val habit: OrgHabit? = null,
    /**
     * Date of the heading's OWN `CLOSED: […]` planning stamp (own region
     * only — child stamps never leak in); null when absent. This is Org's
     * standard completion timestamp, the primary evidence for WHEN an
     * ordinary (non-habit) heading was completed.
     */
    val closedDate: LocalDate? = null,
    /**
     * Date of the LATEST own-region `- State "DONE" from "…" […]` log line
     * (the DONE keyword only — CANCELLED/DROPPED logs are never completion
     * evidence); null when the heading has no such log.
     */
    val stateDoneDate: LocalDate? = null,
    /**
     * The `from` keyword of that latest own State-"DONE" log — the heading's
     * state BEFORE the recorded completion (prior-eligibility evidence for
     * today-retention); null when [stateDoneDate] is null.
     */
    val stateDoneFrom: String? = null,
    /**
     * The note attached to the LATEST own `- State "WAIT" from "…" [ts] \\`
     * log line (Emacs `WAIT(w@)` prompt note: every continuation line under
     * the state line, indent stripped, joined with \n) — exposed ONLY while
     * the heading's own keyword is WAIT, so leaving WAIT always hides the
     * summary even though the LOGBOOK history stays in the file. Null when
     * the latest WAIT transition carries no note (a previous cycle's note is
     * never resurrected) or the heading is not WAIT.
     */
    val waitReason: String? = null,
    val sourceOffset: Int,
    val titleOffset: Int,
    val parentTitles: List<String> = emptyList(),
    val children: List<OrgAgendaEntry> = emptyList()
) {
    val isPlanned: Boolean
        get() = scheduled != null || deadline != null || timestamp != null

    /**
     * Actual completion date from the heading's OWN evidence (CLOSED stamp
     * or State-"DONE" log), whichever is later — never the scheduled date.
     * Null when the heading carries no dated completion evidence at all.
     */
    val ownCompletionDate: LocalDate?
        get() = listOfNotNull(closedDate, stateDoneDate).maxOrNull()
}

data class OrgAgenda(
    val goalText: String,
    /** Shared 总目标 statistics — same object feeds the app card and the widget. */
    val goalStats: OrgGoalStats,
    val daily: DailyAgenda,
    val weekly: WeeklyAgenda,
    val projectControl: ProjectControlAgenda,
    val areaControl: AreaControlAgenda
)

/**
 * Daily sections mirror the Doom "Daily Dashboard" custom agenda command:
 * Today / Next actions / Vibing / Sandbagging / Waiting / Done /
 * Cancelled-dropped / Inbox, in that order.
 */
data class DailyAgenda(
    val today: List<OrgAgendaEntry>,
    val nextActions: List<OrgAgendaEntry>,
    val vibing: List<OrgAgendaEntry>,
    val sandbagging: List<OrgAgendaEntry>,
    val waiting: List<OrgAgendaEntry>,
    val done: List<OrgAgendaEntry>,
    val cancelledDropped: List<OrgAgendaEntry>,
    val inbox: List<OrgAgendaEntry>
)

/** Weekly sections mirror the Doom "Weekly Review" command. */
data class WeeklyAgenda(
    val nextDays: List<OrgAgendaEntry>,
    val stuckProjects: List<OrgAgendaEntry>,
    val vibing: List<OrgAgendaEntry>,
    val sandbagging: List<OrgAgendaEntry>,
    val waiting: List<OrgAgendaEntry>,
    val hold: List<OrgAgendaEntry>,
    val maybe: List<OrgAgendaEntry>,
    val inbox: List<OrgAgendaEntry>
)

data class ProjectControlAgenda(
    val projects: List<OrgAgendaEntry>,
    val stuckProjects: List<OrgAgendaEntry>
)

data class AreaControlAgenda(
    /** Every AREA heading, flat — the Neglected Areas filter's input. */
    val areas: List<OrgAgendaEntry>,
    /**
     * AREA roots (no AREA ancestor) with their full parser subtrees —
     * rendered as parent/child trees like ProjectControlAgenda.projects.
     */
    val areaRoots: List<OrgAgendaEntry>,
    val neglectedAreas: List<OrgAgendaEntry>
)
