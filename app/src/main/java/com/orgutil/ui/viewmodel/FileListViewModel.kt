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

    init {
        loadFiles()
    }

    fun loadFiles() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            
            getOrgFilesUseCase()
                .catch { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message ?: "Unknown error occurred"
                    )
                }
                .collect { files ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        files = files,
                        error = null
                    )
                }
        }
    }

    fun onDocumentTreeSelected(uri: Uri) {
        fileDataSource.storeTreeUri(uri)
        loadFiles()
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
                // Reload files to update the UI with new favorite status
                loadFiles()
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
    val error: String? = null
)