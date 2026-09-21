package com.orgutil.domain.repository

import com.orgutil.domain.agenda.OrgAgenda

interface OrgAgendaRepository {
    suspend fun loadAgenda(): Result<OrgAgenda>
}
