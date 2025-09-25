package com.orgutil.domain.usecase

import android.net.Uri
import com.orgutil.domain.model.OrgFileInfo
import com.orgutil.domain.repository.OrgFileRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GetOrgFilesUseCase @Inject constructor(
    private val repository: OrgFileRepository
) {
    suspend operator fun invoke(uri: Uri? = null): Flow<List<OrgFileInfo>> {
        return repository.getOrgFiles(uri)
    }
}