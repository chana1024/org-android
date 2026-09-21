package com.orgutil.domain.agenda

import android.net.Uri
import java.time.LocalDate

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
    val timestamp: LocalDate? = null,
    val sourceOffset: Int,
    val titleOffset: Int,
    val parentTitles: List<String> = emptyList(),
    val children: List<OrgAgendaEntry> = emptyList()
) {
    val isPlanned: Boolean
        get() = scheduled != null || deadline != null || timestamp != null
}

data class OrgAgenda(
    val goalText: String,
    val daily: DailyAgenda,
    val weekly: WeeklyAgenda,
    val projectControl: ProjectControlAgenda,
    val areaControl: AreaControlAgenda
)

data class DailyAgenda(
    val today: List<OrgAgendaEntry>,
    val nextActions: List<OrgAgendaEntry>,
    val waiting: List<OrgAgendaEntry>,
    val inbox: List<OrgAgendaEntry>
)

data class WeeklyAgenda(
    val nextDays: List<OrgAgendaEntry>,
    val stuckProjects: List<OrgAgendaEntry>,
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
    val areas: List<OrgAgendaEntry>,
    val neglectedAreas: List<OrgAgendaEntry>
)
