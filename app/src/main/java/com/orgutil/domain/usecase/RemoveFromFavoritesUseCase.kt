package com.orgutil.domain.usecase

import android.net.Uri
import com.orgutil.domain.repository.FavoriteRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoveFromFavoritesUseCase @Inject constructor(
    private val favoriteRepository: FavoriteRepository
) {
    suspend operator fun invoke(fileUri: Uri) {
        favoriteRepository.removeFromFavorites(fileUri)
    }
}