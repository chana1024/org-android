package com.orgutil.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import android.provider.DocumentsContract
import com.orgutil.R
import com.orgutil.domain.model.OrgFileInfo
import com.orgutil.ui.components.BreadcrumbCrumb
import com.orgutil.ui.components.BreadcrumbRow
import com.orgutil.ui.components.GitSyncStatusChip
import com.orgutil.ui.components.IndexStatusChip
import com.orgutil.domain.sync.GitSyncStatus
import com.orgutil.ui.viewmodel.FileListQueryMode
import com.orgutil.ui.viewmodel.FileListUiState
import com.orgutil.ui.viewmodel.FileListViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FileListScreen(
    onFileSelected: (Uri, Int?, Int?, String?) -> Unit,
    viewModel: FileListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isFullText = uiState.isFullTextMode

    val searchPlaceholder = if (isFullText)
        stringResource(R.string.search_placeholder_global_search)
    else
        stringResource(R.string.search_placeholder_global_list)

    val documentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            viewModel.onDocumentTreeSelected(it)
        }
    }

    BackHandler(enabled = uiState.canNavigateBack) {
        viewModel.onBackButtonPressed()
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        // 根目录显示固定标题；子目录才显示文件夹名，
                        // 避免 “primary:orgroot” 这类 URI 片段占位。
                        val title = if (uiState.isAtTreeRoot) {
                            stringResource(R.string.file_list_title)
                        } else {
                            uiState.currentDirectory?.name
                                ?: stringResource(R.string.file_list_title)
                        }
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        if (uiState.canNavigateBack) {
                            IconButton(onClick = { viewModel.onBackButtonPressed() }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.back)
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = viewModel::requestGitSync,
                            enabled = uiState.gitSyncStatus !is GitSyncStatus.Running &&
                                uiState.gitSyncStatus !is GitSyncStatus.Enqueued
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudSync,
                                contentDescription = stringResource(R.string.git_sync_now)
                            )
                        }
                        IconButton(
                            onClick = viewModel::refreshIndex,
                            enabled = !uiState.isIndexRequestInFlight
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = stringResource(R.string.index_content)
                            )
                        }
                        IconButton(onClick = viewModel::refreshCurrentLocation) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.refresh)
                            )
                        }
                    }
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 面包屑：根目录一段始终可点（teal），当前目录加粗，
                    // ‹ 与顶栏返回箭头、系统返回手势走同一条弹出路径。
                    val crumbs = buildList {
                        uiState.pathHistory.firstOrNull()?.let {
                            add(BreadcrumbCrumb(label = it.directoryLabel(), isRoot = true))
                        }
                        uiState.pathHistory.drop(1).forEach {
                            add(BreadcrumbCrumb(label = it.directoryLabel()))
                        }
                        uiState.currentDirectoryUri?.let {
                            add(
                                BreadcrumbCrumb(
                                    label = it.directoryLabel(),
                                    isRoot = uiState.pathHistory.isEmpty()
                                )
                            )
                        }
                    }
                    BreadcrumbRow(
                        crumbs = crumbs,
                        onCrumbSelected = viewModel::onBreadcrumbSegmentSelected,
                        onUp = if (uiState.canNavigateBack) {
                            { viewModel.onBackButtonPressed() }
                        } else null
                    )

                    var searchActive by remember { mutableStateOf(false) }
                    SearchBar(
                        query = uiState.searchQuery,
                        onQueryChange = { viewModel.onSearchQueryChanged(it) },
                        onSearch = { viewModel.onSearchQueryChanged(it) },
                        active = searchActive,
                        onActiveChange = { searchActive = it },
                        placeholder = { Text(searchPlaceholder) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (uiState.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                    Icon(Icons.Default.Close, contentDescription = null)
                                }
                            }
                        },
                        windowInsets = WindowInsets(0)
                    ) {
                        if (searchActive) {
                            FileResultsList(
                                uiState = uiState,
                                viewModel = viewModel,
                                onFileSelected = onFileSelected
                            )
                        }
                    }

                    // 模式切换和索引状态合并到一行，Git 状态只在非空闲时
                    // 出现（空闲状态不携带信息），为文件列表留出更多空间。
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                            SegmentedButton(
                                selected = !isFullText,
                                onClick = { viewModel.setSearchMode(FileListQueryMode.FILE_LIST) },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                label = { Text(stringResource(R.string.search_mode_file_list)) }
                            )
                            SegmentedButton(
                                selected = isFullText,
                                onClick = { viewModel.setSearchMode(FileListQueryMode.FULL_TEXT) },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                label = { Text(stringResource(R.string.search_mode_full_text)) }
                            )
                        }
                        IndexStatusChip(
                            status = uiState.indexStatus,
                            isRequestInFlight = uiState.isIndexRequestInFlight
                        )
                    }
                    if (uiState.gitSyncStatus != GitSyncStatus.Idle) {
                        GitSyncStatusChip(status = uiState.gitSyncStatus)
                    }
                }
            }
        },
        floatingActionButton = {
            // 文件夹选择（根目录切换）。快速捕获的 FAB 移到了主屏 Scaffold。
            FloatingActionButton(
                onClick = {
                    documentTreeLauncher.launch(null)
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = stringResource(R.string.select_documents_folder)
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> {
                    LoadingIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                
                uiState.error != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = uiState.error.toString(),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.clearError() }) {
                            Text(stringResource(R.string.dismiss))
                        }
                    }
                }
                
                uiState.files.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = when {
                                uiState.searchQuery.isEmpty() -> stringResource(R.string.no_files_found)
                                uiState.isBrowsingDirectory && !uiState.isFullTextMode -> stringResource(
                                    R.string.no_files_found_in_folder,
                                    uiState.searchQuery
                                )
                                uiState.isFullTextMode -> stringResource(
                                    R.string.no_indexed_content_found,
                                    uiState.searchQuery
                                )
                                else -> stringResource(R.string.no_files_found_for_query, uiState.searchQuery)
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (uiState.searchQuery.isEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { documentTreeLauncher.launch(null) }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.select_documents_folder))
                            }
                        }
                    }
                }
                
                else -> {
                    FileResultsList(
                        uiState = uiState,
                        viewModel = viewModel,
                        onFileSelected = onFileSelected
                    )
                }
            }
        }
    }
}

@Composable
private fun FileResultsList(
    uiState: FileListUiState,
    viewModel: FileListViewModel,
    onFileSelected: (Uri, Int?, Int?, String?) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(uiState.files) { file ->
            FileItem(
                file = file,
                onClick = {
                    if (file.isDirectory) {
                        viewModel.onDirectoryClicked(file)
                    } else {
                        onFileSelected(
                            file.uri,
                            file.searchMatchContentOffset,
                            file.searchPreviewMatchLength,
                            uiState.searchQuery.takeIf { file.searchPreview != null }
                        )
                    }
                },
                onFavoriteToggle = { viewModel.toggleFavorite(file) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileItem(
    file: OrgFileInfo,
    onClick: () -> Unit,
    onFavoriteToggle: () -> Unit = {}
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }
    val hasSearchPreview = !file.searchPreview.isNullOrBlank()

    if (hasSearchPreview) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            SearchResultFileItem(
                file = file,
                onFavoriteToggle = onFavoriteToggle
            )
        }
        return
    }

    ListItem(
        headlineContent = {
            Text(
                text = file.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = if (file.isDirectory) {
            null
        } else {
            {
                Text(
                    text = listOfNotNull(
                        dateFormat.format(Date(file.lastModified)),
                        file.size.takeIf { it > 0 }?.let { "$it bytes" }
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        leadingContent = {
            Icon(
                imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                contentDescription = if (file.isDirectory) "Directory" else "File",
                tint = if (file.isDirectory) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = if (file.isDirectory) {
            {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            {
                IconButton(onClick = onFavoriteToggle) {
                    Icon(
                        imageVector = if (file.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (file.isFavorite) stringResource(R.string.remove_from_favorites) else stringResource(R.string.add_to_favorites),
                        tint = if (file.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        colors = if (file.isDirectory) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        } else {
            ListItemDefaults.colors()
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    )
}

@Composable
private fun SearchResultFileItem(
    file: OrgFileInfo,
    onFavoriteToggle: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = file.uri.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onFavoriteToggle) {
                Icon(
                    imageVector = if (file.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (file.isFavorite) stringResource(R.string.remove_from_favorites) else stringResource(R.string.add_to_favorites),
                    tint = if (file.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Text(
            text = file.highlightedSearchPreview(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun OrgFileInfo.highlightedSearchPreview() = buildAnnotatedString {
    val preview = searchPreview.orEmpty()
    val start = searchPreviewMatchStart ?: -1
    val length = searchPreviewMatchLength ?: 0
    val end = (start + length).coerceAtMost(preview.length)
    if (start !in preview.indices || end <= start) {
        append(preview)
        return@buildAnnotatedString
    }

    append(preview.substring(0, start))
    withStyle(
        SpanStyle(
            background = MaterialTheme.colorScheme.tertiaryContainer,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
    ) {
        append(preview.substring(start, end))
    }
    append(preview.substring(end))
}

/**
 * Short display name for a SAF directory Uri: the document id's last
 * path segment ("primary:org/gtd" -> "gtd"); falls back to the Uri's
 * lastPathSegment, then "~". Navigation is by Uri, never by this name.
 */
private fun Uri.directoryLabel(): String {
    val documentId = try {
        DocumentsContract.getDocumentId(this)
    } catch (_: IllegalArgumentException) {
        try {
            DocumentsContract.getTreeDocumentId(this)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
    return documentId
        ?.substringAfterLast(':')
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
        ?: lastPathSegment?.substringAfterLast('/')
        ?: "~"
}
