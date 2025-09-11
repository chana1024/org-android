package com.orgutil.domain.usecase

import com.orgutil.domain.model.OrgDocument
import com.orgutil.domain.repository.OrgFileRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SaveOrgFileUseCase @Inject constructor(
    private val repository: OrgFileRepository
) {
    suspend operator fun invoke(document: OrgDocument): Result<Unit> {
        return repository.writeOrgFile(document)
    }
}