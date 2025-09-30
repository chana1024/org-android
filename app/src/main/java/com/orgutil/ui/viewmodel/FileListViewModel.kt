package com.orgutil.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.orgutil.domain.model.OrgFileInfo
import com.orgutil.domain.usecase.AddToFavoritesUseCase
import com.orgutil.domain.usecase.GetOrgFilesUseCase
import com.orgutil.domain.usecase.RemoveFromFavoritesUseCase
import com.orgutil.domain.usecase.StoreDocumentTreeUseCase
import com.orgutil.worker.FileIndexerWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.util.Log

@HiltViewModel
class FileListViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val getOrgFilesUseCase: GetOrgFilesUseCase,
    private val addToFavoritesUseCase: AddToFavoritesUseCase,
    private val removeFromFavoritesUseCase: RemoveFromFavoritesUseCase,
    private val storeDocumentTreeUseCase: StoreDocumentTreeUseCase,
    private val fileDataSource: com.orgutil.data.datasource.FileDataSource
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileListUiState())
    val uiState: StateFlow<FileListUiState> = _uiState.asStateFlow()

    init {
        // Load files with stored tree URI if available
        val storedUri = fileDataSource.getStoredTreeUri()
        loadFiles(storedUri)
        // Trigger initial indexing when the app starts
        triggerIndexing()
    }

    fun loadFiles(uri: Uri? = null) {
        Log.d("FileListViewModel", "loadFiles called with uri: $uri")
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val query = _uiState.value.searchQuery

            Log.d("FileListViewModel", "Starting getOrgFilesUseCase with query: '$query'")

            getOrgFilesUseCase(uri, query)
                .catch { error ->
                    Log.e("FileListViewModel", "Error in getOrgFilesUseCase", error)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message ?: "Unknown error occurred"
                    )
                }
                .collect { files ->
                    Log.d("FileListViewModel", "Received ${files.size} files from use case")
                    val currentDirectory = uri?.let {
                        OrgFileInfo(
                            it, it.pathSegments.lastOrNull() ?: "", 0, isDirectory = true,
                            size = 0,
                            isFavorite = false
                        )
                    }
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        files = files,
                        error = null,
                        currentDirectory = currentDirectory
                    )
                    Log.d("FileListViewModel", "Updated UI state with ${files.size} files")
                }
        }
    }

    fun onDirectoryClicked(directory: OrgFileInfo) {
        if (directory.isDirectory) {
            val currentUri = _uiState.value.currentDirectory?.uri
            val newPathHistory = _uiState.value.pathHistory.toMutableList()
            // Add current directory to history before navigating
            // This ensures we can navigate back even from the first subdirectory
            if(currentUri != null) {
                newPathHistory.add(currentUri)
            }
            _uiState.value = _uiState.value.copy(pathHistory = newPathHistory, searchQuery = "")
            // Clear search query when navigating to ensure FileDataSource is used for directory browsing
            loadFiles(directory.uri)
        }
    }

    fun onBackButtonPressed(): Boolean {
        return if (_uiState.value.pathHistory.isNotEmpty()) {
            val newPathHistory = _uiState.value.pathHistory.toMutableList()
            val parentUri = newPathHistory.removeLast()
            _uiState.value = _uiState.value.copy(pathHistory = newPathHistory, searchQuery = "")
            // Clear search query when navigating back to ensure FileDataSource is used for directory browsing
            loadFiles(parentUri)
            true
        } else {
            false
        }
    }

    fun onDocumentTreeSelected(uri: Uri) {
        Log.d("FileListViewModel", "Document tree selected: $uri")
        storeDocumentTreeUseCase(uri)
        _uiState.value = _uiState.value.copy(pathHistory = emptyList(), searchQuery = "")
        loadFiles(uri)
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        loadFiles(_uiState.value.currentDirectory?.uri)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun toggleFavorite(file: OrgFileInfo) {
        viewModelScope.launch {
            try {
                if (file.isFavorite) {
                    removeFromFavoritesUseCase(file.uri)
                } else {
                    addToFavoritesUseCase(file.uri)
                }
                // The list will be updated automatically by the flow
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to toggle favorite: ${e.message}"
                )
            }
        }
    }

    fun triggerIndexing() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isIndexing = true)
                Log.d("FileListViewModel", "Triggering manual indexing...")
                val indexingRequest = OneTimeWorkRequestBuilder<FileIndexerWorker>().build()
                WorkManager.getInstance(context).enqueue(indexingRequest)
                Log.d("FileListViewModel", "Manual indexing request enqueued")
            } catch (e: Exception) {
                Log.e("FileListViewModel", "Failed to start indexing", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to start indexing: ${e.message}",
                    isIndexing = false
                )
            }
        }
    }

    fun refreshIndex() {
        triggerIndexing()
        loadFiles(_uiState.value.currentDirectory?.uri)
    }
}

data class FileListUiState(
    val isLoading: Boolean = false,
    val isIndexing: Boolean = false,
    val files: List<OrgFileInfo> = emptyList(),
    val error: String? = null,
    val currentDirectory: OrgFileInfo? = null,
    val pathHistory: List<Uri> = emptyList(),
    val searchQuery: String = ""
)