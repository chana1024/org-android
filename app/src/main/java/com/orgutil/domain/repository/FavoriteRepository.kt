package com.orgutil.domain.repository

import android.net.Uri

interface FavoriteRepository {
    suspend fun addToFavorites(fileUri: Uri)
    suspend fun removeFromFavorites(fileUri: Uri)
    suspend fun isFavorite(fileUri: Uri): Boolean
    suspend fun getFavoriteUris(): Set<String>
    suspend fun clearFavorites()
}