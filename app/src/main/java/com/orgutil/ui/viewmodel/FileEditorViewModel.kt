package com.orgutil.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orgutil.domain.model.OrgDocument
import com.orgutil.domain.usecase.ReadOrgFileUseCase
import com.orgutil.domain.usecase.SaveOrgFileUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FileEditorViewModel @Inject constructor(
    private val readOrgFileUseCase: ReadOrgFileUseCase,
    private val saveOrgFileUseCase: SaveOrgFileUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileEditorUiState())
    val uiState: StateFlow<FileEditorUiState> = _uiState.asStateFlow()

    fun loadFile(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            
            readOrgFileUseCase(uri)
                .onSuccess { document ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        document = document,
                        editedContent = document.content,
                        hasUnsavedChanges = false,
                        error = null
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message ?: "Failed to load file"
                    )
                }
        }
    }

    fun updateContent(content: String) {
        val currentDocument = _uiState.value.document
        if (currentDocument != null) {
            _uiState.value = _uiState.value.copy(
                editedContent = content,
                hasUnsavedChanges = content != currentDocument.content
            )
        }
    }

    fun saveFile() {
        val currentState = _uiState.value
        val document = currentState.document ?: return
        
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null)
            
            val updatedDocument = document.copy(content = currentState.editedContent)
            
            saveOrgFileUseCase(updatedDocument)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        document = updatedDocument,
                        hasUnsavedChanges = false,
                        saveSuccess = true,
                        error = null
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        error = error.message ?: "Failed to save file"
                    )
                }
        }
    }

    fun clearSaveSuccess() {
        _uiState.value = _uiState.value.copy(saveSuccess = false)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun toggleViewMode() {
        _uiState.value = _uiState.value.copy(isInViewMode = !_uiState.value.isInViewMode)
    }
}

data class FileEditorUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val document: OrgDocument? = null,
    val editedContent: String = "",
    val hasUnsavedChanges: Boolean = false,
    val saveSuccess: Boolean = false,
    val error: String? = null,
    val isInViewMode: Boolean = true
)