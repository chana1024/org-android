package com.orgutil.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orgutil.data.datasource.FileDataSourceImpl
import com.orgutil.domain.model.OrgFileInfo
import com.orgutil.domain.usecase.AddToFavoritesUseCase
import com.orgutil.domain.usecase.GetOrgFilesUseCase
import com.orgutil.domain.usecase.RemoveFromFavoritesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FileListViewModel @Inject constructor(
    private val getOrgFilesUseCase: GetOrgFilesUseCase,
    private val addToFavoritesUseCase: AddToFavoritesUseCase,
    private val removeFromFavoritesUseCase: RemoveFromFavoritesUseCase,
    private val fileDataSource: FileDataSourceImpl
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileListUiState())
    val uiState: StateFlow<FileListUiState> = _uiState.asStateFlow()

    private var allFiles: List<OrgFileInfo> = emptyList()

    init {
        loadFiles()
    }

    fun loadFiles(uri: Uri? = null) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            
            getOrgFilesUseCase(uri)
                .catch { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message ?: "Unknown error occurred"
                    )
                }
                .collect { files ->
                    allFiles = files
                    val currentDirectory = uri?.let {
                        OrgFileInfo(it, it.pathSegments.lastOrNull() ?: "", 0, 0, isDirectory = true)
                    }
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        files = filterFiles(files, _uiState.value.searchQuery),
                        error = null,
                        currentDirectory = currentDirectory
                    )
                }
        }
    }

    fun onDirectoryClicked(directory: OrgFileInfo) {
        if (directory.isDirectory) {
            val newPathHistory = _uiState.value.pathHistory + (_uiState.value.currentDirectory?.uri)
            _uiState.value = _uiState.value.copy(pathHistory = newPathHistory, searchQuery = "")
            loadFiles(directory.uri)
        }
    }

    fun onBackButtonPressed(): Boolean {
        return if (_uiState.value.pathHistory.isNotEmpty()) {
            val newPathHistory = _uiState.value.pathHistory.toMutableList()
            val parentUri = newPathHistory.removeLast()
            _uiState.value = _uiState.value.copy(pathHistory = newPathHistory, searchQuery = "")
            loadFiles(parentUri)
            true
        } else {
            false
        }
    }

    fun onDocumentTreeSelected(uri: Uri) {
        fileDataSource.storeTreeUri(uri)
        _uiState.value = _uiState.value.copy(pathHistory = emptyList(), searchQuery = "")
        loadFiles(uri)
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(
            searchQuery = query,
            files = filterFiles(allFiles, query)
        )
    }

    private fun filterFiles(files: List<OrgFileInfo>, query: String): List<OrgFileInfo> {
        if (query.isBlank()) {
            return files
        }
        return files.filter { it.name.contains(query, ignoreCase = true) }
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
                loadFiles(_uiState.value.currentDirectory?.uri)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to toggle favorite: ${e.message}"
                )
            }
        }
    }
}

data class FileListUiState(
    val isLoading: Boolean = false,
    val files: List<OrgFileInfo> = emptyList(),
    val error: String? = null,
    val currentDirectory: OrgFileInfo? = null,
    val pathHistory: List<Uri> = emptyList(),
    val searchQuery: String = ""
)