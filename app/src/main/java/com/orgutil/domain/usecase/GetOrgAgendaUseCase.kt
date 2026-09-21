package com.orgutil.domain.usecase

import com.orgutil.domain.agenda.OrgAgenda
import com.orgutil.domain.repository.OrgAgendaRepository
import javax.inject.Inject

class GetOrgAgendaUseCase @Inject constructor(
    private val orgAgendaRepository: OrgAgendaRepository
) {
    suspend operator fun invoke(): Result<OrgAgenda> {
        return orgAgendaRepository.loadAgenda()
    }
}
