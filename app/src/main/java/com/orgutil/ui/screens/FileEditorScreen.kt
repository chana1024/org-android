package com.orgutil.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.orgutil.R
import com.orgutil.ui.components.OrgRenderer
import com.orgutil.ui.viewmodel.FileEditorViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileEditorScreen(
    fileUriString: String?,
    highlightOffset: Int? = null,
    highlightLength: Int? = null,
    highlightQuery: String? = null,
    onNavigateBack: () -> Unit,
    viewModel: FileEditorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val editorFocusRequester = remember { FocusRequester() }
    var editorValue by remember { mutableStateOf(TextFieldValue("")) }
    
    // State for global fold toggle
    var globalFoldState by remember { mutableStateOf<Boolean?>(null) }
    
    LaunchedEffect(fileUriString, highlightOffset, highlightLength, highlightQuery) {
        fileUriString?.let { encodedUriString ->
            try {
                val uri = Uri.parse(encodedUriString)
                viewModel.loadFile(
                    uri = uri,
                    highlightOffset = highlightOffset,
                    highlightLength = highlightLength,
                    highlightQuery = highlightQuery
                )
            } catch (e: Exception) {
                // Handle invalid URI
            }
        }
    }

    LaunchedEffect(uiState.document?.uri) {
        if (uiState.document != null) {
            editorValue = TextFieldValue(uiState.editedContent)
        }
    }

    LaunchedEffect(uiState.highlightStart, uiState.highlightEnd, uiState.document?.uri) {
        val start = uiState.highlightStart
        val end = uiState.highlightEnd
        if (start != null && end != null && uiState.document != null) {
            editorValue = TextFieldValue(
                text = uiState.editedContent,
                selection = TextRange(start, end)
            )
            editorFocusRequester.requestFocus()
        }
    }
    
    // Show save success message
    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            // Auto-clear success message after a delay
            kotlinx.coroutines.delay(2000)
            viewModel.clearSaveSuccess()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(uiState.document?.fileName ?: "Loading...") 
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    // Global fold/unfold button (only show in view mode with content)
                    if (uiState.document != null && !uiState.isLoading && uiState.isInViewMode) {
                        IconButton(
                            onClick = { 
                                // Toggle between fold all and unfold all
                                val currentlyFolded = globalFoldState != false
                                globalFoldState = if (currentlyFolded) false else true
                            }
                        ) {
                            Icon(
                                imageVector = if (globalFoldState == false) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (globalFoldState == false) "Fold All" else "Expand All"
                            )
                        }
                    }
                    
                    // View mode toggle
                    if (uiState.document != null && !uiState.isLoading) {
                        IconButton(
                            onClick = { viewModel.toggleViewMode() }
                        ) {
                            Icon(
                                imageVector = if (uiState.isInViewMode) Icons.Default.Edit else Icons.Default.Visibility,
                                contentDescription = if (uiState.isInViewMode) "Edit" else "View"
                            )
                        }
                    }
                    
                    // Save button
                    if (uiState.hasUnsavedChanges && !uiState.isLoading && !uiState.isInViewMode) {
                        IconButton(
                            onClick = { viewModel.saveFile() },
                            enabled = !uiState.isSaving
                        ) {
                            if (uiState.isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Save,
                                    contentDescription = stringResource(R.string.save)
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                
                uiState.error != null -> {
                    val errorMessage:String = uiState.error.toString()
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.clearError() }) {
                            Text("Dismiss")
                        }
                    }
                }
                
                uiState.document != null -> {
                    val currentDocument = uiState.document
                    Column(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // Save success indicator
                        if (uiState.saveSuccess) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Text(
                                    text = stringResource(R.string.file_saved),
                                    modifier = Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                        
                        // Content area - either rendered view or text editor
                        if (uiState.isInViewMode) {
                            // Rendered org-mode view
                            if (currentDocument!=null) {
                                OrgRenderer(
                                    nodes = currentDocument.nodes,
                                    modifier = Modifier.weight(1f),
                                    globalToggleState = globalFoldState,
                                    preamble = currentDocument.preamble
                                )
                            }
                        } else {
                            // Text editor
                            OutlinedTextField(
                                value = editorValue,
                                onValueChange = {
                                    editorValue = it
                                    viewModel.updateContent(it.text)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(16.dp)
                                    .focusRequester(editorFocusRequester),
                                label = { Text("Content") },
                                placeholder = { Text("Enter your org-mode content here...") },
                                maxLines = Int.MAX_VALUE,
                                singleLine = false
                            )
                        }
                    }
                }
            }
        }
    }
}
