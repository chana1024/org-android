package com.orgutil.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orgutil.domain.usecase.GetOrgAgendaUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AgendaViewModel @Inject constructor(
    private val getOrgAgendaUseCase: GetOrgAgendaUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgendaUiState(isLoading = true))
    val uiState: StateFlow<AgendaUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun setMode(mode: AgendaViewMode) {
        _uiState.value = _uiState.value.copy(selectedMode = mode)
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            getOrgAgendaUseCase()
                .onSuccess { agenda ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        agenda = agenda,
                        error = null
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message ?: "Failed to load agenda"
                    )
                }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
