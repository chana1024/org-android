package com.orgutil.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.orgutil.R
import com.orgutil.domain.sync.GitSyncStatus
import com.orgutil.ui.viewmodel.SyncViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SyncScreen(
    viewModel: SyncViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Re-check All files access when the user returns from system settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        viewModel.refresh()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.sync_title),
            style = MaterialTheme.typography.headlineSmall
        )

        GitSyncStatusMonitor(status = uiState.syncStatus)

        uiState.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        uiState.successMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Storage access
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.sync_storage_section),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.sync_storage_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (uiState.hasStorageAccess) {
                    Text(
                        text = stringResource(R.string.sync_storage_granted),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                val perAppIntent = Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                try {
                                    context.startActivity(perAppIntent)
                                } catch (_: ActivityNotFoundException) {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                        )
                                    }
                                }
                            } else {
                                legacyPermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            }
                        }
                    ) {
                        Text(stringResource(R.string.sync_storage_grant))
                    }
                }
            }
        }

        // Repository
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.sync_repo_section),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = uiState.resolvedRepoRoot
                        ?: stringResource(R.string.sync_repo_not_found),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (uiState.resolvedRepoRoot != null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
                OutlinedTextField(
                    value = uiState.repoRootOverride,
                    onValueChange = viewModel::onRepoRootOverrideChanged,
                    label = { Text(stringResource(R.string.sync_repo_override_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = viewModel::saveRepoRootOverride) {
                    Text(stringResource(R.string.sync_repo_use_path))
                }
            }
        }

        // Remote configuration
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.sync_remote_section),
                    style = MaterialTheme.typography.titleMedium
                )
                OutlinedTextField(
                    value = uiState.remoteUrl,
                    onValueChange = viewModel::onRemoteUrlChanged,
                    label = { Text(stringResource(R.string.sync_remote_url_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = uiState.username,
                    onValueChange = viewModel::onUsernameChanged,
                    label = { Text(stringResource(R.string.sync_username_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = uiState.token,
                    onValueChange = viewModel::onTokenChanged,
                    label = { Text(stringResource(R.string.sync_token_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = viewModel::saveConfiguration) {
                        Text(stringResource(R.string.sync_save))
                    }
                    OutlinedButton(
                        onClick = viewModel::testConnection,
                        enabled = uiState.isRemoteConfigured && !uiState.isLoading
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(stringResource(R.string.sync_test_connection))
                        }
                    }
                }
            }
        }

        // Sync actions
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.sync_actions_section),
                    style = MaterialTheme.typography.titleMedium
                )
                Button(
                    onClick = viewModel::requestSync,
                    enabled = !uiState.isSyncRequestInFlight,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudSync,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.sync_now))
                }
                if (uiState.lastSyncTime > 0) {
                    val dateFormat = remember {
                        SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                    }
                    Text(
                        text = stringResource(
                            R.string.sync_last_sync,
                            dateFormat.format(Date(uiState.lastSyncTime))
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                uiState.snapshot?.let { snapshot ->
                    val branchLabel = snapshot.branch
                        ?: stringResource(R.string.sync_branch_detached)
                    Text(
                        text = stringResource(
                            R.string.sync_repo_state,
                            branchLabel,
                            snapshot.ahead,
                            snapshot.behind
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.sync_auto_on_launch),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = uiState.autoSyncEnabled,
                        onCheckedChange = viewModel::setAutoSyncEnabled
                    )
                }
            }
        }
    }
}

@Composable
private fun GitSyncStatusMonitor(status: GitSyncStatus) {
    when (status) {
        GitSyncStatus.Idle -> Unit
        GitSyncStatus.Enqueued -> StatusRow(stringResource(R.string.sync_status_queued))
        is GitSyncStatus.Running -> StatusRow(stringResource(R.string.sync_status_running, status.step))
        is GitSyncStatus.Succeeded -> StatusRow(
            stringResource(R.string.sync_status_succeeded, status.detail),
            color = MaterialTheme.colorScheme.primary
        )
        is GitSyncStatus.Conflict -> StatusRow(
            stringResource(
                R.string.sync_status_conflict,
                status.files.joinToString(", ")
            ),
            color = MaterialTheme.colorScheme.tertiary
        )
        is GitSyncStatus.Failed -> StatusRow(
            stringResource(R.string.sync_status_failed, status.message),
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun StatusRow(text: String, color: androidx.compose.ui.graphics.Color? = null) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color ?: MaterialTheme.colorScheme.onSurface
    )
}
