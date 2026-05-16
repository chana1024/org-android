package com.orgutil.ui.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orgutil.domain.indexing.FileIndexRequestResult
import com.orgutil.domain.indexing.FileIndexScheduler
import com.orgutil.domain.model.OrgFileInfo
import com.orgutil.domain.usecase.AddToFavoritesUseCase
import com.orgutil.domain.usecase.GetOrgFilesUseCase
import com.orgutil.domain.usecase.GetStoredDocumentTreeUseCase
import com.orgutil.domain.usecase.RemoveFromFavoritesUseCase
import com.orgutil.domain.usecase.StoreDocumentTreeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val storeDocumentTreeUseCase: StoreDocumentTreeUseCase,
    private val getStoredDocumentTreeUseCase: GetStoredDocumentTreeUseCase,
    private val fileIndexScheduler: FileIndexScheduler
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileListUiState())
    val uiState: StateFlow<FileListUiState> = _uiState.asStateFlow()

    private var loadFilesJob: Job? = null
    private var searchDebounceJob: Job? = null

    init {
        bootstrap()
    }

    fun refreshCurrentLocation() {
        searchDebounceJob?.cancel()
        loadFiles(_uiState.value.currentDirectoryUri)
    }

    /** 切换搜索模式：所有文件 ↔ 全文搜索 */
    fun setSearchMode(queryMode: FileListQueryMode) {
        val state = _uiState.value
        if (state.queryMode == queryMode) return

        _uiState.value = state.withSearchMode(queryMode)
        refreshCurrentLocation()
    }

    fun onDirectoryClicked(directory: OrgFileInfo) {
        if (!directory.isDirectory) return

        val currentUri = _uiState.value.currentDirectoryUri
        val newPathHistory = buildList {
            addAll(_uiState.value.pathHistory)
            if (currentUri != null) {
                add(currentUri)
            }
        }
        navigateTo(directory.uri, newPathHistory)
    }

    fun onBackButtonPressed(): Boolean {
        if (_uiState.value.pathHistory.isEmpty()) {
            return false
        }

        val newPathHistory = _uiState.value.pathHistory.toMutableList()
        val parentUri = newPathHistory.removeAt(newPathHistory.lastIndex)
        navigateTo(parentUri, newPathHistory)
        return true
    }

    fun onDocumentTreeSelected(uri: Uri) {
        safeLogD("FileListViewModel", "Document tree selected: $uri")
        storeDocumentTreeUseCase(uri)
        navigateTo(uri, emptyList())
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.value = _uiState.value.globalQueryChanged(query)
        scheduleSearchRefresh()
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
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to toggle favorite: ${e.message}"
                )
            }
        }
    }

    fun triggerIndexing() {
        requestIndexing()
    }

    fun refreshIndex() {
        requestIndexing()
    }

    private fun bootstrap() {
        val storedUri = getStoredDocumentTreeUseCase()
        // 启动时加载根目录，但不改变搜索模式（保持 FULL_TEXT）
        if (storedUri != null) {
            _uiState.value = _uiState.value.copy(
                currentDirectory = storedUri.toDirectoryInfo(),
                pathHistory = emptyList()
            )
        }
        loadFiles(storedUri)
        triggerIndexing()
    }

    private fun loadFiles(uri: Uri?) {
        safeLogD("FileListViewModel", "loadFiles called with uri: $uri")
        loadFilesJob?.cancel()

        loadFilesJob = viewModelScope.launch {
            val state = _uiState.value
            val useDatabase = state.queryMode == FileListQueryMode.FULL_TEXT
            val effectiveUri = uri

            _uiState.value = _uiState.value.loading()
            safeLogD(
                "FileListViewModel",
                "Starting getOrgFilesUseCase with query: '${state.searchQuery}', useDatabase: $useDatabase, effectiveUri: $effectiveUri"
            )

            getOrgFilesUseCase(effectiveUri, state.searchQuery, useDatabase)
                .catch { error ->
                    safeLogE("FileListViewModel", "Error in getOrgFilesUseCase", error)
                    _uiState.value = _uiState.value.loadFailed(
                        error.message ?: "Unknown error occurred"
                    )
                }
                .collect { files ->
                    safeLogD("FileListViewModel", "Received ${files.size} files from use case")
                    _uiState.value = _uiState.value.loaded(files = files)
                    safeLogD("FileListViewModel", "Updated UI state with ${files.size} files")
                }
        }
    }

    private fun scheduleSearchRefresh() {
        searchDebounceJob?.cancel()
        searchDebounceJob = viewModelScope.launch {
            delay(300)
            loadFiles(_uiState.value.currentDirectoryUri)
        }
    }

    private fun navigateTo(uri: Uri, pathHistory: List<Uri>) {
        searchDebounceJob?.cancel()
        _uiState.value = _uiState.value.navigatedTo(
            directory = uri.toDirectoryInfo(),
            pathHistory = pathHistory
        )
        loadFiles(uri)
    }

    private fun requestIndexing() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.indexRequestStarted()
            safeLogD("FileListViewModel", "Requesting file indexing work...")
            when (val result = fileIndexScheduler.requestIndexing()) {
                FileIndexRequestResult.Enqueued -> {
                    safeLogD("FileListViewModel", "File indexing work request enqueued")
                    _uiState.value = _uiState.value.indexRequestEnqueued()
                }
                is FileIndexRequestResult.Failed -> {
                    val message = "Failed to start indexing: ${result.message}"
                    safeLogE("FileListViewModel", message)
                    _uiState.value = _uiState.value.indexRequestFailed(message)
                }
            }
        }
    }
}

private fun Uri.toDirectoryInfo(): OrgFileInfo = OrgFileInfo(
    uri = this,
    name = pathSegments.lastOrNull().orEmpty(),
    lastModified = 0,
    isDirectory = true,
    size = 0,
    isFavorite = false
)

private fun safeLogD(tag: String, message: String) {
    runCatching { Log.d(tag, message) }
}

private fun safeLogE(tag: String, message: String, throwable: Throwable? = null) {
    runCatching {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
}
