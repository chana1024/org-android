package com.orgutil.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.orgutil.domain.repository.FavoriteRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoriteRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : FavoriteRepository {

    private val prefs: SharedPreferences = context.getSharedPreferences("org_util_prefs", Context.MODE_PRIVATE)
    private val DOCUMENT_TREE_URI_KEY = "document_tree_uri"
    private val FAVORITES_FILE_URI_KEY = "favorites_file_uri"
    private val FAVORITES_FILE_NAME = ".orgutil_favorites"

    override suspend fun addToFavorites(fileUri: Uri) :Unit= withContext(Dispatchers.IO) {
        val favorites = getFavoriteUris().toMutableSet()
        favorites.add(fileUri.toString())
        saveFavoritesToFile(favorites)
    }

    override suspend fun removeFromFavorites(fileUri: Uri):Unit = withContext(Dispatchers.IO) {
        val favorites = getFavoriteUris().toMutableSet()
        favorites.remove(fileUri.toString())
        saveFavoritesToFile(favorites)
    }

    override suspend fun isFavorite(fileUri: Uri): Boolean = withContext(Dispatchers.IO) {
        getFavoriteUris().contains(fileUri.toString())
    }

    override suspend fun getFavoriteUris(): Set<String> = withContext(Dispatchers.IO) {
        try {
            val favoritesFile = getFavoritesFile()
            if (favoritesFile?.exists() == true) {
                readFavoritesFromFile(favoritesFile)
            } else {
                emptySet()
            }
        } catch (e: Exception) {
            // Fallback to empty set if file reading fails
            emptySet()
        }
    }

    override suspend fun clearFavorites():Unit = withContext(Dispatchers.IO) {
        saveFavoritesToFile(emptySet())
    }

    private suspend fun getFavoritesFile(): DocumentFile? = withContext(Dispatchers.IO) {
        val treeUri = getStoredTreeUri() ?: return@withContext null
        
        try {
            val documentFile = DocumentFile.fromTreeUri(context, treeUri)
            if (documentFile?.exists() != true || !documentFile.isDirectory) {
                return@withContext null
            }

            // First, try to use cached favorites file URI if available
            val cachedFileUri = getCachedFavoritesFileUri()
            if (cachedFileUri != null) {
                val cachedFile = DocumentFile.fromSingleUri(context, cachedFileUri)
                if (cachedFile?.exists() == true && cachedFile.isFile) {
                    return@withContext cachedFile
                } else {
                    // Cached file is invalid, clear it
                    clearCachedFavoritesFileUri()
                }
            }

            // Search for existing favorites file (look for files that start with our name)
            val existingFile = documentFile.listFiles().find { file ->
                file.isFile && file.name != null && (
                    file.name == FAVORITES_FILE_NAME ||
                    file.name!!.startsWith(FAVORITES_FILE_NAME) ||
                    file.name!!.contains("orgutil") && file.name!!.contains("favorites")
                )
            }

            if (existingFile != null) {
                // Cache the found file URI for future use
                cacheFavoritesFileUri(existingFile.uri)
                return@withContext existingFile
            }

            // Create new favorites file
            val newFile = documentFile.createFile("text/plain", FAVORITES_FILE_NAME)
            if (newFile != null) {
                // Cache the created file URI
                cacheFavoritesFileUri(newFile.uri)
                return@withContext newFile
            }

            null
        } catch (e: Exception) {
            android.util.Log.e("FavoriteRepository", "Failed to get favorites file", e)
            null
        }
    }

    private fun getCachedFavoritesFileUri(): Uri? {
        val uriString = prefs.getString(FAVORITES_FILE_URI_KEY, null) ?: return null
        return try {
            Uri.parse(uriString)
        } catch (e: Exception) {
            null
        }
    }

    private fun cacheFavoritesFileUri(uri: Uri) {
        prefs.edit().putString(FAVORITES_FILE_URI_KEY, uri.toString()).apply()
    }

    private fun clearCachedFavoritesFileUri() {
        prefs.edit().remove(FAVORITES_FILE_URI_KEY).apply()
    }

    private suspend fun readFavoritesFromFile(favoritesFile: DocumentFile): Set<String> = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(favoritesFile.uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.readLines().filter { it.isNotBlank() }.toSet()
                }
            } ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private suspend fun saveFavoritesToFile(favorites: Set<String>) = withContext(Dispatchers.IO) {
        try {
            val favoritesFile = getFavoritesFile()
            if (favoritesFile != null) {
                context.contentResolver.openOutputStream(favoritesFile.uri)?.use { outputStream ->
                    OutputStreamWriter(outputStream).use { writer ->
                        favorites.forEach { favorite ->
                            writer.write("$favorite\n")
                        }
                        writer.flush() // Ensure content is written
                    }
                }
            }else{
               android.util.Log.e("FavoriteRepository", "Failed to get favorites file")
            }
        } catch (e: Exception) {
            // Log error for debugging - in production, this should use proper logging
            android.util.Log.e("FavoriteRepository", "Failed to save favorites to file", e)
        }
    }

    private fun getStoredTreeUri(): Uri? {
        val uriString = prefs.getString(DOCUMENT_TREE_URI_KEY, null) ?: return null
        return try {
            Uri.parse(uriString)
        } catch (e: Exception) {
            null
        }
    }
}