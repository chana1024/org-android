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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import android.provider.DocumentsContract
import com.orgutil.R
import com.orgutil.domain.indexing.FileIndexStatus
import com.orgutil.domain.model.OrgFileInfo
import com.orgutil.ui.components.BreadcrumbCrumb
import com.orgutil.ui.components.BreadcrumbRow
import com.orgutil.ui.components.GitSyncStatusChip
import com.orgutil.ui.components.IndexStatusChip
import com.orgutil.ui.theme.OrgMono
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
                        // Draft title: "Files" + mono repo chip at root;
                        // folder name inside a directory.
                        if (uiState.isAtTreeRoot) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.file_list_title),
                                    fontWeight = FontWeight.SemiBold
                                )
                                uiState.currentDirectoryUri?.let {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainer,
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp, MaterialTheme.colorScheme.outlineVariant
                                        )
                                    ) {
                                        Text(
                                            text = it.directoryLabel(),
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = uiState.currentDirectory?.name
                                    ?: stringResource(R.string.file_list_title),
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
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
                        // Draft: mono "N files" inline in the app bar, with
                        // a spinner while indexing runs.
                        val indexRunning = uiState.isIndexRequestInFlight ||
                            uiState.indexStatus == FileIndexStatus.Running
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (indexRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                            Text(
                                text = "${uiState.files.size} files",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
                    // Draft header: at root there is no breadcrumb row — the
                    // repo chip in the title carries it. Inside a directory:
                    // breadcrumb + (rare) git status chip on one line.
                    if (uiState.canNavigateBack) {
                        val crumbs = buildList {
                            uiState.pathHistory.firstOrNull()?.let {
                                add(BreadcrumbCrumb(label = it.directoryLabel(), isRoot = true))
                            }
                            uiState.pathHistory.drop(1).forEach {
                                add(BreadcrumbCrumb(label = it.directoryLabel()))
                            }
                            uiState.currentDirectoryUri?.let {
                                add(BreadcrumbCrumb(label = it.directoryLabel()))
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            BreadcrumbRow(
                                crumbs = crumbs,
                                onCrumbSelected = viewModel::onBreadcrumbSegmentSelected,
                                onUp = { viewModel.onBackButtonPressed() },
                                modifier = Modifier.weight(1f)
                            )
                            if (uiState.gitSyncStatus != GitSyncStatus.Idle) {
                                GitSyncStatusChip(status = uiState.gitSyncStatus)
                            }
                        }
                    }

                    var searchActive by remember { mutableStateOf(false) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
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
                            windowInsets = WindowInsets(0),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (searchActive) {
                                FileResultsList(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onFileSelected = onFileSelected
                                )
                            }
                        }

                        // Draft CJK chip position carries the query-mode
                        // toggle (本目录 ↔ 全库), replacing the old
                        // segmented row that the draft doesn't have.
                        FilterChip(
                            selected = isFullText,
                            onClick = {
                                viewModel.setSearchMode(
                                    if (isFullText) FileListQueryMode.FILE_LIST
                                    else FileListQueryMode.FULL_TEXT
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(R.string.search_mode_full_text),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        )
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
    val onItemClicked: (OrgFileInfo) -> Unit = { file ->
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
    }

    val isSearchMode = uiState.files.any { !it.searchPreview.isNullOrBlank() }
    if (isSearchMode) {
        // Search results keep per-item cards (preview layout).
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(uiState.files) { file ->
                FileItem(
                    file = file,
                    onClick = { onItemClicked(file) },
                    onFavoriteToggle = { viewModel.toggleFavorite(file) }
                )
            }
        }
    } else {
        // Draft grouping: the whole list is one card of rows with dividers.
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column {
                        uiState.files.forEachIndexed { index, file ->
                            if (index > 0) {
                                HorizontalDivider(
                                    thickness = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                )
                            }
                            FileItem(
                                file = file,
                                onClick = { onItemClicked(file) },
                                onFavoriteToggle = { viewModel.toggleFavorite(file) }
                            )
                        }
                    }
                }
            }
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

    // Draft row: leading icon in a bordered box, mono filename, short mono
    // metadata, star favorite / directory chevron at the right.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = androidx.compose.foundation.BorderStroke(
                1.dp, MaterialTheme.colorScheme.outlineVariant
            )
        ) {
            Icon(
                imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                contentDescription = if (file.isDirectory) "Directory" else "File",
                tint = if (file.isDirectory) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(4.dp)
                    .size(16.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = OrgMono),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (file.isFavorite) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
            if (!file.isDirectory) {
                val shortDate = remember {
                    SimpleDateFormat("MMM dd", Locale.getDefault())
                }
                Text(
                    text = listOfNotNull(
                        shortDate.format(Date(file.lastModified)),
                        file.size.takeIf { it > 0 }?.let {
                            if (it >= 1024) "%.1f KB".format(it / 1024f) else "$it B"
                        }
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (file.isDirectory) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            IconButton(onClick = onFavoriteToggle, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = if (file.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (file.isFavorite) stringResource(R.string.remove_from_favorites) else stringResource(R.string.add_to_favorites),
                    tint = if (file.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
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
