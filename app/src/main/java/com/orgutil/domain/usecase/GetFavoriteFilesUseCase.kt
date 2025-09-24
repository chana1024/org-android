package com.orgutil.domain.usecase

import com.orgutil.domain.model.OrgFileInfo
import com.orgutil.domain.repository.FavoriteRepository
import com.orgutil.domain.repository.OrgFileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GetFavoriteFilesUseCase @Inject constructor(
    private val orgFileRepository: OrgFileRepository,
    private val favoriteRepository: FavoriteRepository
) {
    suspend operator fun invoke(): Flow<List<OrgFileInfo>> {
        return orgFileRepository.getOrgFiles().map { files ->
            val favoriteUris = favoriteRepository.getFavoriteUris()
            files.filter { file ->
                favoriteUris.contains(file.uri.toString())
            }.map { file ->
                file.copy(isFavorite = true)
            }
        }
    }
}