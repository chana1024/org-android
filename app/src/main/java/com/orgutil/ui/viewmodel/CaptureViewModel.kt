package com.orgutil.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orgutil.domain.sync.GitSyncScheduler
import com.orgutil.domain.usecase.AddToCaptureFileUseCase
import com.orgutil.domain.usecase.CaptureOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CaptureUiState(
    val isLoading: Boolean = false,
    val successMessage: String? = null,
    val error: String? = null
)

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val addToCaptureFileUseCase: AddToCaptureFileUseCase,
    private val gitSyncScheduler: GitSyncScheduler
) : ViewModel() {

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    /**
     * Plain capture — the legacy path, byte-for-byte. [onResult] fires once
     * per accepted press with the ACTUAL save outcome (never a timer), so a
     * caller may clear its draft only on real success and close only on real
     * success.
     */
    fun addToCaptureFile(
        content: String,
        onResult: ((Boolean) -> Unit)? = null
    ) = addToCaptureFile(content, CaptureOptions(), onResult)

    fun addToCaptureFile(
        content: String,
        options: CaptureOptions,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        // One accepted press at a time: isLoading is set synchronously (the
        // immediate main dispatcher runs the body up to the first suspension),
        // so a second tap — even before recomposition — cannot enqueue a
        // duplicate append.
        if (_uiState.value.isLoading) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                successMessage = null
            )

            // Keep the plain path on the legacy String overload.
            val result = if (options.isPlain) {
                addToCaptureFileUseCase(content)
            } else {
                addToCaptureFileUseCase(content, options)
            }

            result
                .onSuccess {
                    // The capture itself finished and was verified. Sync
                    // scheduling must never turn a finished save into a
                    // failure (loading stuck, draft kept, retry duplicating
                    // the append) — isolate it from the capture result.
                    runCatching { gitSyncScheduler.requestSyncIfConfigured() }
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        successMessage = "内容已成功添加到 gtd/inbox.org！"
                    )
                    onResult?.invoke(true)
                }
                .onFailure { exception ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "添加失败: ${exception.message}"
                    )
                    onResult?.invoke(false)
                }
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(
            successMessage = null,
            error = null
        )
    }
}
