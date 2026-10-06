package com.orgutil.ui.viewmodel

import com.orgutil.domain.agenda.OrgAgenda
import com.orgutil.domain.agenda.OrgAgendaEntry

enum class AgendaViewMode {
    DAILY,
    WEEKLY,
    PROJECTS,
    AREAS
}

/** Bounded digest of a planned archive run, shown before confirmation. */
data class ArchivePlanUi(
    val count: Int,
    val totalChars: Int,
    val preview: List<String>,
    val moreCount: Int
)

data class AgendaUiState(
    val isLoading: Boolean = false,
    val agenda: OrgAgenda? = null,
    val selectedMode: AgendaViewMode = AgendaViewMode.DAILY,
    val error: String? = null,
    val archivePlan: ArchivePlanUi? = null,
    val archiveBusy: Boolean = false,
    /** Failure notice for an archive run (success goes to the snackbar flow). */
    val archiveNotice: String? = null,
    /** Entry whose TODO keyword the picker dialog is targeting. */
    val todoEditTarget: OrgAgendaEntry? = null,
    val todoEditBusy: Boolean = false,
    /** Failure notice for a TODO keyword edit (success goes to the snackbar flow). */
    val todoNotice: String? = null,
    /** Entry whose habit schedule dialog is targeting (valid source identity only). */
    val habitEditTarget: OrgAgendaEntry? = null,
    val habitBusy: Boolean = false,
    /** Failure notice for a STYLE=habit change (success goes to the snackbar flow). */
    val habitNotice: String? = null,
    /** Entry whose planning-date dialog is targeting (valid source identity only). */
    val planningEditTarget: OrgAgendaEntry? = null,
    val planningBusy: Boolean = false,
    /** Failure notice for a SCHEDULED/DEADLINE change (success goes to the snackbar flow). */
    val planningNotice: String? = null,
    /** Entry whose SCHEDULED-only quick editor is targeting (valid source identity only). */
    val scheduleEditTarget: OrgAgendaEntry? = null,
    /**
     * Entry whose WAIT 等待原因 editor is targeting — either a fresh WAIT
     * transition (picked WAIT in the keyword dialog) or an existing WAIT
     * item (chip re-tap / reason summary tap) editing its current note.
     */
    val waitReasonTarget: OrgAgendaEntry? = null,
    val waitReasonBusy: Boolean = false,
    /** Failure notice for a WAIT reason write (success goes to the snackbar flow). */
    val waitNotice: String? = null,
    /** Failure notice for a pomodoro start (success goes to the snackbar flow). */
    val pomodoroNotice: String? = null,
    val pomodoroBusy: Boolean = false
)
