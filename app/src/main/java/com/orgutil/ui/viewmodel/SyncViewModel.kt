package com.orgutil.ui.viewmodel

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.orgutil.data.gcal.GcalAuthManager
import com.orgutil.data.gcal.GcalConfigStore
import com.orgutil.data.gcal.GcalStateStore
import com.orgutil.data.gcal.GcalSyncState
import com.orgutil.data.gcal.GcalTokenResult
import com.orgutil.data.datasource.ThemeChoice
import com.orgutil.ui.theme.ThemeController
import com.orgutil.widget.AgendaWidgetUpdater
import com.orgutil.widget.QuickCaptureWidgetProvider
import com.orgutil.domain.gcal.GcalScheduler
import com.orgutil.domain.gcal.GcalSyncRequestResult
import com.orgutil.domain.gcal.GcalSyncStatus
import com.orgutil.domain.repository.GitSyncRepository
import com.orgutil.domain.sync.GitRepoSnapshot
import com.orgutil.domain.sync.GitSyncScheduler
import com.orgutil.domain.sync.GitSyncRequestResult
import com.orgutil.domain.sync.GitSyncStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
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
    val error: String? = null,
    // Google Calendar (one-way GTD -> calendar)
    val gcalAuthorized: Boolean = false,
    val gcalAutoSyncEnabled: Boolean = false,
    val gcalLastSyncTime: Long = 0L,
    val gcalStatus: GcalSyncStatus = GcalSyncStatus.Idle,
    val gcalTrackedCount: Int = 0,
    val gcalLastDetail: String? = null,
    val gcalLastError: String? = null,
    val gcalNeedsAuthorization: Boolean = false,
    val gcalPlayServicesAvailable: Boolean = true,
    val isGcalAuthBusy: Boolean = false
) {
    val isRemoteConfigured: Boolean get() = remoteUrl.isNotBlank()
    val isConfigSaved: Boolean get() = isRemoteConfigured && username.isNotBlank() && token.isNotBlank()
    val isGcalBusy: Boolean
        get() = isGcalAuthBusy || gcalStatus is GcalSyncStatus.Running || gcalStatus is GcalSyncStatus.Enqueued
}

@HiltViewModel
class SyncViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gitSyncRepository: GitSyncRepository,
    private val gitSyncScheduler: GitSyncScheduler,
    private val gcalAuthManager: GcalAuthManager,
    private val gcalScheduler: GcalScheduler,
    private val gcalConfigStore: GcalConfigStore,
    private val gcalStateStore: GcalStateStore,
    private val themeController: ThemeController,
    private val agendaWidgetUpdater: AgendaWidgetUpdater
) : ViewModel() {

    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    /**
     * Live theme selection shown by the Appearance card. selectTheme()
     * persists + flips the app-wide Compose state (whole app restyles on the
     * same frame) and then pushes the FULL refresh broadcast so every
     * existing home-screen Agenda widget re-renders — header layout swap AND
     * notifyAppWidgetViewDataChanged, which rebuilds the collection rows,
     * keyword badges and habit cells under the new palette — plus the Quick
     * Capture widget's own re-layout. Broadcasts are cheap no-ops when no
     * widget instance exists.
     */
    private val _themeChoice = MutableStateFlow(themeController.current)
    val themeChoice: StateFlow<ThemeChoice> = _themeChoice.asStateFlow()

    fun selectTheme(choice: ThemeChoice) {
        themeController.select(choice)
        _themeChoice.value = choice
        agendaWidgetUpdater.refreshAgenda()
        QuickCaptureWidgetProvider.updateAllWidgets(context)
    }

    /** One-shot consent requests the screen must launch in the foreground. */
    private val _gcalConsentRequests = Channel<PendingIntent>(Channel.BUFFERED)
    val gcalConsentRequests: Flow<PendingIntent> = _gcalConsentRequests.receiveAsFlow()

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
        viewModelScope.launch { reloadGcalState() }
        viewModelScope.launch {
            gcalScheduler.observeSync().collect { status ->
                _uiState.update { it.copy(gcalStatus = status) }
                if (status !is GcalSyncStatus.Running && status !is GcalSyncStatus.Enqueued) {
                    reloadGcalState()
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
        viewModelScope.launch { reloadGcalState() }
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

    // ---- Google Calendar ----

    /**
     * Foreground connect/reauthorize: runs the silent authorization first and
     * surfaces the consent [PendingIntent] through [gcalConsentRequests] when
     * Google needs user interaction; the screen launches it.
     */
    fun connectGcal(activityContext: Context) {
        if (_uiState.value.isGcalBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isGcalAuthBusy = true) }
            when (val result = gcalAuthManager.authorize(activityContext)) {
                is GcalTokenResult.Token -> {
                    gcalConfigStore.setAuthorized(true)
                    _uiState.update {
                        it.copy(
                            isGcalAuthBusy = false,
                            gcalAuthorized = true,
                            error = null,
                            successMessage = "Google Calendar connected"
                        )
                    }
                    requestGcalSync()
                }
                is GcalTokenResult.ConsentNeeded -> {
                    _uiState.update { it.copy(isGcalAuthBusy = false) }
                    _gcalConsentRequests.trySend(result.pendingIntent)
                }
                is GcalTokenResult.Error -> {
                    _uiState.update { it.copy(isGcalAuthBusy = false, error = result.message) }
                }
            }
        }
    }

    /** Consent flow result forwarded by the screen's activity-result launcher. */
    fun onGcalConsentResult(resultOk: Boolean, data: Intent?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isGcalAuthBusy = true) }
            val token = data?.let { gcalAuthManager.tokenFromIntent(it) }
            when {
                token != null -> onGcalTokenAcquired()
                !resultOk -> _uiState.update {
                    it.copy(
                        isGcalAuthBusy = false,
                        error = "Google authorization was not completed"
                    )
                }
                else -> {
                    // Consent returned without a token: the silent path should now succeed.
                    when (val result = gcalAuthManager.acquireTokenSilently()) {
                        is GcalTokenResult.Token -> onGcalTokenAcquired()
                        else -> _uiState.update {
                            it.copy(
                                isGcalAuthBusy = false,
                                error = (result as? GcalTokenResult.Error)?.message
                                    ?: "Google authorization did not return a token"
                            )
                        }
                    }
                }
            }
        }
    }

    fun onGcalConsentLaunchFailed() {
        _uiState.update {
            it.copy(isGcalAuthBusy = false, error = "Could not open the Google authorization screen")
        }
    }

    private fun onGcalTokenAcquired() {
        gcalConfigStore.setAuthorized(true)
        _uiState.update {
            it.copy(
                isGcalAuthBusy = false,
                gcalAuthorized = true,
                gcalNeedsAuthorization = false,
                error = null,
                successMessage = "Google Calendar connected"
            )
        }
        requestGcalSync()
    }

    fun requestGcalSync() {
        if (!gcalConfigStore.isAuthorized()) {
            _uiState.update { it.copy(error = "Connect Google Calendar first") }
            return
        }
        when (val result = gcalScheduler.requestSync()) {
            GcalSyncRequestResult.Enqueued -> Unit
            GcalSyncRequestResult.NotConfigured ->
                _uiState.update { it.copy(error = "Google Calendar is not connected") }
            is GcalSyncRequestResult.Failed ->
                _uiState.update { it.copy(error = result.message) }
        }
    }

    /** Auto-sync toggle: arms/cancels the 30-minute periodic worker. */
    fun setGcalAutoSyncEnabled(enabled: Boolean) {
        gcalConfigStore.setAutoSyncEnabled(enabled)
        if (enabled) {
            when (val result = gcalScheduler.ensurePeriodicSync()) {
                is GcalSyncRequestResult.Failed ->
                    _uiState.update { it.copy(error = result.message) }
                else -> Unit
            }
        } else {
            gcalScheduler.cancelPeriodicSync()
        }
        _uiState.update { it.copy(gcalAutoSyncEnabled = enabled) }
    }

    /**
     * Disconnect: stops scheduled sync and revokes the Google grant. Local
     * token/state are cleared, but calendar events are never deleted by a
     * disconnect - reconnecting re-adopts them via their Org ID.
     */
    fun disconnectGcal(activityContext: Context) {
        if (_uiState.value.isGcalBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isGcalAuthBusy = true) }
            gcalScheduler.cancelPeriodicSync()
            val revokeMessage = runCatching { gcalAuthManager.revokeGrant(activityContext) }.getOrNull()
            gcalAuthManager.clearTokenCache()
            gcalConfigStore.setAuthorized(false)
            gcalConfigStore.setAutoSyncEnabled(false)
            gcalConfigStore.setLastSyncTime(0L)
            gcalStateStore.mutate { GcalSyncState() }
            _uiState.update {
                it.copy(
                    isGcalAuthBusy = false,
                    gcalAuthorized = false,
                    gcalAutoSyncEnabled = false,
                    gcalLastSyncTime = 0L,
                    gcalStatus = GcalSyncStatus.Idle,
                    gcalTrackedCount = 0,
                    gcalLastDetail = null,
                    gcalLastError = null,
                    gcalNeedsAuthorization = false,
                    error = revokeMessage
                )
            }
        }
    }

    private suspend fun reloadGcalState() {
        val state = gcalStateStore.read()
        _uiState.update {
            it.copy(
                gcalAuthorized = gcalConfigStore.isAuthorized(),
                gcalAutoSyncEnabled = gcalConfigStore.isAutoSyncEnabled(),
                gcalLastSyncTime = gcalConfigStore.getLastSyncTime(),
                gcalTrackedCount = state.items.size,
                gcalLastDetail = state.lastResult?.detail,
                gcalLastError = state.lastResult?.error,
                gcalNeedsAuthorization = state.lastResult?.needsAuthorization == true,
                gcalPlayServicesAvailable = gcalAuthManager.isPlayServicesAvailable()
            )
        }
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
