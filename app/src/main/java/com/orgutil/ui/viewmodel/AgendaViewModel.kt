package com.orgutil.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orgutil.domain.files.OrgFileChangeNotifier
import com.orgutil.domain.usecase.GetOrgAgendaUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AgendaViewModel @Inject constructor(
    private val getOrgAgendaUseCase: GetOrgAgendaUseCase,
    fileChangeNotifier: OrgFileChangeNotifier
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgendaUiState(isLoading = true))
    val uiState: StateFlow<AgendaUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null

    init {
        refresh()
        observeFileChanges(fileChangeNotifier)
    }

    fun setMode(mode: AgendaViewMode) {
        _uiState.value = _uiState.value.copy(selectedMode = mode)
    }

    /**
     * Reloads the agenda. With [showLoading] false the current content stays
     * on screen (loading only when there is nothing to show yet) - used for
     * file-change events and tab re-entry so the list does not flicker.
     */
    fun refresh(showLoading: Boolean = true) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            if (showLoading || _uiState.value.agenda == null) {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            }
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

    private fun observeFileChanges(fileChangeNotifier: OrgFileChangeNotifier) {
        viewModelScope.launch {
            fileChangeNotifier.changes
                // Debounce without @FlowPreview: a new emission cancels the
                // pending reload, coalescing bursts (e.g. agent write sessions).
                .collectLatest {
                    delay(FILE_CHANGE_DEBOUNCE_MS)
                    refresh(showLoading = false)
                }
        }
    }

    private companion object {
        const val FILE_CHANGE_DEBOUNCE_MS = 300L
    }
}
