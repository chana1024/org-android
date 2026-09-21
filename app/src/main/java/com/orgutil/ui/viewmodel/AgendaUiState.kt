package com.orgutil.ui.viewmodel

import com.orgutil.domain.agenda.OrgAgenda

enum class AgendaViewMode {
    DAILY,
    WEEKLY,
    PROJECTS,
    AREAS
}

data class AgendaUiState(
    val isLoading: Boolean = false,
    val agenda: OrgAgenda? = null,
    val selectedMode: AgendaViewMode = AgendaViewMode.DAILY,
    val error: String? = null
)
