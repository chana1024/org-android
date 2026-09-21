package com.orgutil.ui.viewmodel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.orgutil.domain.repository.GitSyncRepository
import com.orgutil.domain.sync.GitRepoSnapshot
import com.orgutil.domain.sync.GitSyncScheduler
import com.orgutil.domain.sync.GitSyncRequestResult
import com.orgutil.domain.sync.GitSyncStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SyncUiState(
    val isLoading: Boolean = false,
    val hasStorageAccess: Boolean = false,
    val remoteUrl: String = "",
    val username: String = "",
    val token: String = "",
    val repoRootOverride: String = "",
    val resolvedRepoRoot: String? = null,
    val autoSyncEnabled: Boolean = true,
    val lastSyncTime: Long = 0L,
    val syncStatus: GitSyncStatus = GitSyncStatus.Idle,
    val isSyncRequestInFlight: Boolean = false,
    val snapshot: GitRepoSnapshot? = null,
    val successMessage: String? = null,
    val error: String? = null
) {
    val isRemoteConfigured: Boolean get() = remoteUrl.isNotBlank()
    val isConfigSaved: Boolean get() = isRemoteConfigured && username.isNotBlank() && token.isNotBlank()
}

@HiltViewModel
class SyncViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gitSyncRepository: GitSyncRepository,
    private val gitSyncScheduler: GitSyncScheduler
) : ViewModel() {

    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    init {
        loadConfig()
        viewModelScope.launch {
            gitSyncScheduler.observeSync().collect { status ->
                _uiState.update {
                    it.copy(
                        syncStatus = status,
                        isSyncRequestInFlight = status is GitSyncStatus.Running ||
                            status is GitSyncStatus.Enqueued
                    )
                }
            }
        }
    }

    /** Recomputes everything cheaply; safe to call on every ON_RESUME. */
    fun refresh() {
        _uiState.update { it.copy(hasStorageAccess = hasStorageAccess()) }
        viewModelScope.launch {
            val root = gitSyncRepository.resolveRepoRoot().getOrNull()?.absolutePath
            _uiState.update { it.copy(resolvedRepoRoot = root) }
            if (root != null) {
                val snapshot = gitSyncRepository.getRepoSnapshot()
                _uiState.update { it.copy(snapshot = snapshot) }
            }
        }
    }

    fun saveConfiguration() {
        val state = _uiState.value
        if (state.remoteUrl.isBlank()) {
            _uiState.update { it.copy(error = "Remote URL is required") }
            return
        }
        val url = state.remoteUrl.trim()
        val allowedScheme = listOf("https://", "http://", "git://", "file://").any { url.startsWith(it) }
        if (!allowedScheme) {
            _uiState.update {
                it.copy(error = "Remote URL must be an https/http/git/file URL")
            }
            return
        }
        if (state.username.isBlank() || state.token.isBlank()) {
            _uiState.update { it.copy(error = "Username and token are required") }
            return
        }

        viewModelScope.launch {
            try {
                gitSyncRepository.saveRemoteUrl(state.remoteUrl.trim())
                gitSyncRepository.saveCredentials(state.username.trim(), state.token.trim())
                _uiState.update {
                    it.copy(successMessage = "Configuration saved", error = null)
                }
                clearMessagesAfterDelay()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Failed to save configuration") }
            }
        }
    }

    fun saveRepoRootOverride() {
        val path = _uiState.value.repoRootOverride.trim()
        if (path.isBlank()) {
            _uiState.update { it.copy(error = "Repo path is required") }
            return
        }
        viewModelScope.launch {
            gitSyncRepository.saveRepoRootOverride(path)
            refresh()
            val resolved = _uiState.value.resolvedRepoRoot
            _uiState.update {
                if (resolved != null) {
                    it.copy(successMessage = "Repository found: $resolved", error = null)
                } else {
                    it.copy(error = "Path does not contain a .git directory")
                }
            }
            clearMessagesAfterDelay()
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = gitSyncRepository.testConnection()
            _uiState.update { current ->
                current.copy(
                    isLoading = false,
                    successMessage = result.exceptionOrNull()?.let { null } ?: "Connection successful",
                    error = result.exceptionOrNull()?.message
                )
            }
            clearMessagesAfterDelay()
        }
    }

    fun requestSync() {
        when (val result = gitSyncScheduler.requestSync()) {
            GitSyncRequestResult.Enqueued -> Unit
            GitSyncRequestResult.NotConfigured ->
                _uiState.update { it.copy(error = "Git sync is not configured") }
            is GitSyncRequestResult.Failed ->
                _uiState.update { it.copy(error = result.message) }
        }
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        gitSyncRepository.setAutoSyncEnabled(enabled)
        _uiState.update { it.copy(autoSyncEnabled = enabled) }
    }

    fun onRemoteUrlChanged(value: String) = _uiState.update { it.copy(remoteUrl = value) }

    fun onUsernameChanged(value: String) = _uiState.update { it.copy(username = value) }

    fun onTokenChanged(value: String) = _uiState.update { it.copy(token = value) }

    fun onRepoRootOverrideChanged(value: String) =
        _uiState.update { it.copy(repoRootOverride = value) }

    fun clearError() = _uiState.update { it.copy(error = null) }

    fun clearSuccessMessage() = _uiState.update { it.copy(successMessage = null) }

    private fun loadConfig() {
        val credentials = gitSyncRepository.getCredentials()
        _uiState.update {
            it.copy(
                hasStorageAccess = hasStorageAccess(),
                remoteUrl = gitSyncRepository.getRemoteUrl().orEmpty(),
                username = credentials?.first.orEmpty(),
                token = credentials?.second.orEmpty(),
                repoRootOverride = gitSyncRepository.getRepoRootOverride().orEmpty(),
                autoSyncEnabled = gitSyncRepository.isAutoSyncEnabled(),
                lastSyncTime = gitSyncRepository.getLastSyncTime()
            )
        }
    }

    private fun clearMessagesAfterDelay() {
        viewModelScope.launch {
            delay(MESSAGE_CLEAR_DELAY_MS)
            _uiState.update { it.copy(successMessage = null) }
        }
    }

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private companion object {
        const val MESSAGE_CLEAR_DELAY_MS = 3000L
    }
}
