package com.orgutil.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orgutil.domain.model.OrgFileInfo
import com.orgutil.domain.usecase.GetFavoriteFilesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val getFavoriteFilesUseCase: GetFavoriteFilesUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(FavoritesUiState())
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()

    init {
        loadFavoriteFiles()
    }

    fun loadFavoriteFiles() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            
            getFavoriteFilesUseCase()
                .catch { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message ?: "Unknown error occurred"
                    )
                }
                .collect { favoriteFiles ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        favoriteFiles = favoriteFiles,
                        error = null
                    )
                }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}

data class FavoritesUiState(
    val isLoading: Boolean = false,
    val favoriteFiles: List<OrgFileInfo> = emptyList(),
    val error: String? = null
)