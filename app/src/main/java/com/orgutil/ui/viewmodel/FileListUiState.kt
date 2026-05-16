package com.orgutil.ui.viewmodel

import android.net.Uri
import com.orgutil.domain.model.OrgFileInfo

/**
 * 搜索模式，只有两种：
 * - FILE_LIST：文件列表搜索（扫文件系统，文件名匹配）
 * - FULL_TEXT：全文搜索（查 Room FTS 索引，文件名 + 正文）
 */
enum class FileListQueryMode {
    FILE_LIST,
    FULL_TEXT
}

data class FileListUiState(
    val isLoading: Boolean = false,
    val isIndexRequestInFlight: Boolean = false,
    val hasPendingIndexRefresh: Boolean = false,
    val files: List<OrgFileInfo> = emptyList(),
    val error: String? = null,
    val currentDirectory: OrgFileInfo? = null,
    val pathHistory: List<Uri> = emptyList(),
    val searchQuery: String = "",
    val queryMode: FileListQueryMode = FileListQueryMode.FULL_TEXT
) {
    /** 是否处于子目录浏览 */
    val isBrowsingDirectory: Boolean
        get() = currentDirectory != null

    /** 是否在查全文索引 */
    val isFullTextMode: Boolean
        get() = queryMode == FileListQueryMode.FULL_TEXT

    val canNavigateBack: Boolean
        get() = pathHistory.isNotEmpty()

    val currentDirectoryUri: Uri?
        get() = currentDirectory?.uri

    /** 无条件可以切换搜索模式（不再被目录模式锁死） */
    fun withSearchMode(queryMode: FileListQueryMode): FileListUiState {
        return copy(queryMode = queryMode)
    }

    fun navigatedTo(directory: OrgFileInfo, pathHistory: List<Uri>): FileListUiState {
        return copy(
            currentDirectory = directory,
            pathHistory = pathHistory,
            searchQuery = "",
            // 进子目录不清除搜索模式，保留 FILE_LIST / FULL_TEXT
        )
    }

    fun globalQueryChanged(query: String): FileListUiState {
        return copy(searchQuery = query)
    }

    fun loading(): FileListUiState {
        return copy(isLoading = true, error = null)
    }

    fun loaded(files: List<OrgFileInfo>): FileListUiState {
        return copy(
            isLoading = false,
            files = files,
            error = null
        )
    }

    fun loadFailed(message: String): FileListUiState {
        return copy(
            isLoading = false,
            error = message
        )
    }

    fun indexRequestStarted(): FileListUiState {
        return copy(isIndexRequestInFlight = true)
    }

    fun indexRequestEnqueued(): FileListUiState {
        return copy(
            isIndexRequestInFlight = false,
            hasPendingIndexRefresh = true,
            error = null
        )
    }

    fun indexRequestFailed(message: String): FileListUiState {
        return copy(
            error = message,
            isIndexRequestInFlight = false
        )
    }
}
