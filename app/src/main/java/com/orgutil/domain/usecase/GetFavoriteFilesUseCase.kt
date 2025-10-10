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
        return favoriteRepository.getFavoriteUrisFlow().map { favoriteUris ->
            // Directly get file info for each favorite URI instead of scanning entire directory
            favoriteUris.mapNotNull { uriString ->
                try {
                    val uri = android.net.Uri.parse(uriString)
                    orgFileRepository.getFileInfo(uri)?.copy(isFavorite = true)
                } catch (e: Exception) {
                    // Skip invalid URIs
                    null
                }
            }
        }
    }
}