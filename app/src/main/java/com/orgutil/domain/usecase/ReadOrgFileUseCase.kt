package com.orgutil.domain.usecase

import android.net.Uri
import com.orgutil.domain.model.OrgDocument
import com.orgutil.domain.repository.OrgFileRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReadOrgFileUseCase @Inject constructor(
    private val repository: OrgFileRepository
) {
    suspend operator fun invoke(uri: Uri): Result<OrgDocument> {
        return repository.readOrgFile(uri)
    }
}