package com.orgutil.domain.agenda

import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OrgAgendaBuilder @Inject constructor() {

    fun build(
        entries: List<OrgAgendaEntry>,
        goalText: String,
        today: LocalDate = LocalDate.now()
    ): OrgAgenda {
        val allEntries = entries.flattenAgendaEntries()
        val allProjects = allEntries.filter { it.todo == "PROJ" }
        // GTD Projects renders parent/child trees: a PROJ nested under another
        // PROJ stays inside its parent branch instead of repeating as its own
        // root, so only PROJ headings with no PROJ ancestor become roots.
        val projectRoots = collectProjectRoots(entries)
        val stuckProjects = allProjects.filter { it.isStuckProject() }
        val areas = allEntries.filter { it.todo == "AREA" }
        // GTD Areas mirrors the Projects tree: an AREA nested under another
        // AREA stays inside its parent branch (rendered once), so only AREA
        // headings with no AREA ancestor become roots. Roots keep their full
        // parser subtree — children without a TODO keyword, intermediate
        // ancestors and nested AREA/PROJ branches included, each with its own
        // uri/sourceOffset identity.
        val areaRoots = collectAreaRoots(entries)
        // Shared 总目标 stats: computed from the same parsed roots as every
        // section — one scan, one population (see OrgGoalStatsCalculator).
        val goalStats = OrgGoalStatsCalculator.compute(entries, today)

        return OrgAgenda(
            goalText = goalText,
            goalStats = goalStats,
            daily = DailyAgenda(
                // Doom's Today agenda block skips SANDBAGGING/VIBING states
                // (my/org-agenda-skip-if-excluded-todo) and — like Org
                // itself — never lists completed entries: a DONE/CANCELLED/
                // DROPPED headline stays out of Today even when its
                // SCHEDULED/DEADLINE falls on today (it belongs to the Done
                // / Cancelled-dropped blocks below). Completed-state is the
                // parsed TODO keyword only (canonical DONE_KEYWORDS), never
                // tags or done-today log lines — a habit whose repeater
                // already reset it to an active keyword stays on Today.
                today = allEntries.filter {
                    (it.hasPlanningOn(today) || it.isHabitDue(today)) &&
                        it.todo !in EXCLUDED_FROM_DAY_AGENDA &&
                        (it.todo == null || it.todo !in OrgAgendaParser.DONE_KEYWORDS)
                },
                nextActions = allEntries.filter { it.todo == "NEXT" && !it.isPlanned },
                // Vibing keeps planned items visible (Doom shows the planned
                // date in the prefix); Sandbagging skips planned items.
                vibing = allEntries.filter { it.todo == "VIBING" },
                sandbagging = allEntries.filter { it.todo == "SANDBAGGING" && !it.isPlanned },
                waiting = allEntries.filter { it.todo == "WAIT" && !it.isPlanned },
                done = allEntries.filter { it.todo == "DONE" },
                cancelledDropped = allEntries.filter { it.todo == "CANCELLED" || it.todo == "DROPPED" },
                inbox = allEntries.filter { it.isInboxCapture() }
            ),
            weekly = WeeklyAgenda(
                nextDays = allEntries.filter {
                    it.hasPlanningBetween(today, today.plusDays(13)) &&
                        it.todo !in EXCLUDED_FROM_DAY_AGENDA
                },
                stuckProjects = stuckProjects,
                vibing = allEntries.filter { it.todo == "VIBING" },
                sandbagging = allEntries.filter { it.todo == "SANDBAGGING" },
                waiting = allEntries.filter { it.todo == "WAIT" },
                hold = allEntries.filter { it.todo == "HOLD" },
                maybe = allEntries.filter { it.todo == "MAYBE" },
                inbox = allEntries.filter { it.isInboxCapture() }
            ),
            projectControl = ProjectControlAgenda(
                projects = projectRoots,
                stuckProjects = stuckProjects
            ),
            areaControl = AreaControlAgenda(
                areas = areas,
                areaRoots = areaRoots,
                neglectedAreas = areas.filter { it.isNeglectedArea(today) }
            )
        )
    }

    private companion object {
        /** States the Doom day/next-14-days agenda blocks skip. */
        private val EXCLUDED_FROM_DAY_AGENDA = setOf("SANDBAGGING", "VIBING")

        /** The GTD capture file; entries here are inbox items by location. */
        private const val INBOX_FILE_NAME = "inbox.org"
    }

    /**
     * An inbox capture is an entry that lives in gtd/inbox.org and carries no
     * TODO keyword on its headline — i.e. an unprocessed capture that has not
     * been given any state yet. Any parsed keyword (TODO, NEXT, DONE, ...) or
     * tag, inherited or own, does not affect this; entries in other files
     * never count.
     */
    private fun OrgAgendaEntry.isInboxCapture(): Boolean {
        return fileName == INBOX_FILE_NAME && todo == null
    }

    private fun List<OrgAgendaEntry>.flattenAgendaEntries(): List<OrgAgendaEntry> {
        return flatMap { entry -> listOf(entry) + entry.children.flattenAgendaEntries() }
    }

    /**
     * Root projects for the GTD Projects tree: every PROJ heading that has no
     * PROJ ancestor. The walk stops descending at a PROJ heading so nested
     * projects render once, inside their parent's branch; a PROJ under a
     * non-PROJ heading (e.g. an AREA) is still an independent root.
     */
    private fun collectProjectRoots(entries: List<OrgAgendaEntry>): List<OrgAgendaEntry> {
        return entries.flatMap { entry ->
            if (entry.todo == "PROJ") {
                listOf(entry)
            } else {
                collectProjectRoots(entry.children)
            }
        }
    }

    /**
     * Root areas for the GTD Areas tree, exactly the Projects rule: every
     * AREA heading with no AREA ancestor. The walk stops descending at an
     * AREA heading so a nested AREA renders once, inside its parent's
     * branch; an AREA under a non-AREA heading is still an independent
     * root. Each root keeps its full parser subtree, so no descendant
     * (TODO-less headings included) is dropped or duplicated.
     */
    private fun collectAreaRoots(entries: List<OrgAgendaEntry>): List<OrgAgendaEntry> {
        return entries.flatMap { entry ->
            if (entry.todo == "AREA") {
                listOf(entry)
            } else {
                collectAreaRoots(entry.children)
            }
        }
    }

    private fun OrgAgendaEntry.isStuckProject(): Boolean {
        return !children.flattenAgendaEntries().any { it.todo == "NEXT" || it.todo == "WAIT" }
    }

    private fun OrgAgendaEntry.isNeglectedArea(today: LocalDate): Boolean {
        val descendants = children.flattenAgendaEntries()
        return descendants.none { it.todo == "PROJ" } && !hasFutureTrigger(today)
    }

    private fun OrgAgendaEntry.hasFutureTrigger(today: LocalDate): Boolean {
        return planningDates().any { !it.isBefore(today) }
    }

    private fun OrgAgendaEntry.hasPlanningOn(date: LocalDate): Boolean {
        return planningDates().any { it == date }
    }

    /**
     * A habit stays on today's agenda while its scheduled occurrence is
     * today or overdue (org-habit: the repeater keeps it due until done,
     * matching org-scheduled-past-days visibility for habits). Future
     * scheduled habits only appear once their day arrives.
     */
    private fun OrgAgendaEntry.isHabitDue(today: LocalDate): Boolean {
        val habit = habit ?: return false
        return !habit.scheduled.isAfter(today)
    }

    private fun OrgAgendaEntry.hasPlanningBetween(start: LocalDate, end: LocalDate): Boolean {
        return planningDates().any { !it.isBefore(start) && !it.isAfter(end) }
    }

    private fun OrgAgendaEntry.planningDates(): List<LocalDate> {
        return listOfNotNull(scheduled, deadline, timestamp)
    }
}
