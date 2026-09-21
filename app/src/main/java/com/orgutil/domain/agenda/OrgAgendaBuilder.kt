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
        val projects = allEntries.filter { it.todo == "PROJ" }
        val stuckProjects = projects.filter { it.isStuckProject() }
        val areas = allEntries.filter { it.todo == "AREA" }

        return OrgAgenda(
            goalText = goalText,
            daily = DailyAgenda(
                today = allEntries.filter { it.hasPlanningOn(today) },
                nextActions = allEntries.filter { it.todo == "NEXT" && !it.isPlanned },
                waiting = allEntries.filter { it.todo == "WAIT" && !it.isPlanned },
                inbox = allEntries.filter { it.tags.contains("INBOX") }
            ),
            weekly = WeeklyAgenda(
                nextDays = allEntries.filter { it.hasPlanningBetween(today, today.plusDays(13)) },
                stuckProjects = stuckProjects,
                waiting = allEntries.filter { it.todo == "WAIT" },
                hold = allEntries.filter { it.todo == "HOLD" },
                maybe = allEntries.filter { it.todo == "MAYBE" },
                inbox = allEntries.filter { it.tags.contains("INBOX") }
            ),
            projectControl = ProjectControlAgenda(
                projects = projects,
                stuckProjects = stuckProjects
            ),
            areaControl = AreaControlAgenda(
                areas = areas,
                neglectedAreas = areas.filter { it.isNeglectedArea(today) }
            )
        )
    }

    private fun List<OrgAgendaEntry>.flattenAgendaEntries(): List<OrgAgendaEntry> {
        return flatMap { entry -> listOf(entry) + entry.children.flattenAgendaEntries() }
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

    private fun OrgAgendaEntry.hasPlanningBetween(start: LocalDate, end: LocalDate): Boolean {
        return planningDates().any { !it.isBefore(start) && !it.isAfter(end) }
    }

    private fun OrgAgendaEntry.planningDates(): List<LocalDate> {
        return listOfNotNull(scheduled, deadline, timestamp)
    }
}
