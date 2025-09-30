package com.orgutil.domain.repository

import android.net.Uri
import kotlinx.coroutines.flow.Flow

interface FavoriteRepository {
    suspend fun addToFavorites(fileUri: Uri)
    suspend fun removeFromFavorites(fileUri: Uri)
    suspend fun isFavorite(fileUri: Uri): Boolean
    fun getFavoriteUrisFlow(): Flow<Set<String>>
    suspend fun getFavoriteUris(): Set<String>
    suspend fun clearFavorites()
}
