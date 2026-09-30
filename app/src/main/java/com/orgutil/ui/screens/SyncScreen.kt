package com.orgutil.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.orgutil.ui.components.OrgTopBar
import com.orgutil.ui.components.OrgTopBarIcon
import com.orgutil.ui.components.OrgMonoChip
import com.orgutil.ui.theme.LocalExtendedColors
import com.orgutil.ui.components.OrgTopBar
import com.orgutil.ui.components.OrgTopBarIcon
import com.orgutil.ui.components.OrgMonoChip
import com.orgutil.ui.theme.OrgMono
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

    // Re-check All files access / battery exemption when returning from settings
    var ignoringBatteryOptimizations by remember { mutableStateOf(false) }
    fun checkBattery() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        ignoringBatteryOptimizations =
            pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }
    checkBattery()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                checkBattery()
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

    Scaffold(
        topBar = {
            OrgTopBar(
                title = stringResource(R.string.sync_title),
                subtitle = if (uiState.remoteUrl.isNotBlank()) {
                    {
                        Text(
                            text = uiState.remoteUrl,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                } else null,
                actions = {
                    OrgTopBarIcon(
                        icon = Icons.Default.CloudSync,
                        contentDescription = stringResource(R.string.sync_now),
                        enabled = !uiState.isSyncRequestInFlight,
                        onClick = viewModel::requestSync
                    )
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Storage access warning (draft amber-left-border banner style)
            if (!uiState.hasStorageAccess) {
                WarningBanner(
                    icon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                    text = stringResource(R.string.sync_storage_explanation),
                    actionText = stringResource(R.string.sync_storage_grant),
                    onAction = {
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
                )
            }

            // Battery optimization warning (draft banner)
            if (!ignoringBatteryOptimizations) {
                WarningBanner(
                    icon = { Icon(Icons.Default.BatteryAlert, contentDescription = null) },
                    text = "后台同步可能被系统优化中断,建议忽略电池优化",
                    actionText = "去设置",
                    onAction = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    }
                )
            }

            SyncHeroCard(uiState = uiState, onSyncNow = viewModel::requestSync)

            uiState.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            uiState.successMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Repository
            ConfigCard(title = stringResource(R.string.sync_repo_section)) {
                Text(
                    text = uiState.resolvedRepoRoot
                        ?: stringResource(R.string.sync_repo_not_found),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = OrgMono),
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

            // Remote configuration
            ConfigCard(title = stringResource(R.string.sync_remote_section)) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

            // Schedule (draft card)
            ConfigCard(title = "Schedule") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.sync_auto_on_launch),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "全自动后台拉取与合并",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.autoSyncEnabled,
                        onCheckedChange = viewModel::setAutoSyncEnabled
                    )
                }
            }
        }
    }
}

/** Draft amber-left-border warning banner with an inline action. */
@Composable
private fun WarningBanner(
    icon: @Composable () -> Unit,
    text: String,
    actionText: String,
    onAction: () -> Unit
) {
    val extended = LocalExtendedColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                extended.warningContainer.copy(alpha = 0.35f),
                RoundedCornerShape(topStart = 4.dp, topEnd = 8.dp, bottomEnd = 8.dp, bottomStart = 4.dp)
            )
            .border(
                width = 3.dp,
                color = extended.warningContainer,
                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 8.dp, bottomEnd = 8.dp, bottomStart = 4.dp)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        icon()
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onAction) {
            Text(actionText, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * Draft hero card: status line + mono last-sync + branch chip, pull/push
 * stat grid, then the full-width teal Sync-now button.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SyncHeroCard(
    uiState: com.orgutil.ui.viewmodel.SyncUiState,
    onSyncNow: () -> Unit
) {
    val status = uiState.syncStatus
    val busy = status is GitSyncStatus.Running || status is GitSyncStatus.Enqueued
    val label: String
    val labelColor: Color
    val showSpinner: Boolean
    val showCheckIcon: Boolean
    when {
        busy -> {
            label = if (status is GitSyncStatus.Running) {
                stringResource(R.string.sync_status_running, status.step)
            } else {
                stringResource(R.string.sync_status_queued)
            }
            labelColor = MaterialTheme.colorScheme.primary
            showSpinner = true
            showCheckIcon = false
        }
        status is GitSyncStatus.Conflict -> {
            label = stringResource(R.string.sync_status_conflict, status.files.joinToString(", "))
            labelColor = MaterialTheme.colorScheme.tertiary
            showSpinner = false
            showCheckIcon = false
        }
        status is GitSyncStatus.Failed -> {
            label = stringResource(R.string.sync_status_failed, status.message)
            labelColor = MaterialTheme.colorScheme.error
            showSpinner = false
            showCheckIcon = false
        }
        else -> {
            label = if (status is GitSyncStatus.Succeeded) {
                stringResource(R.string.sync_status_succeeded, status.detail)
            } else {
                "Up to date"
            }
            labelColor = MaterialTheme.colorScheme.primary
            showSpinner = false
            showCheckIcon = true
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column {
            if (busy) {
                LinearWavyProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (showSpinner) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
                if (showCheckIcon) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = labelColor
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleSmall,
                        color = labelColor,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    val meta = buildString {
                        if (uiState.lastSyncTime > 0) {
                            append(
                                SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                                    .format(Date(uiState.lastSyncTime))
                            )
                        }
                        uiState.snapshot?.let { snapshot ->
                            append(" · ${snapshot.ahead + snapshot.behind} changes")
                        }
                    }
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                uiState.snapshot?.branch?.let { branch ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    ) {
                        Text(
                            text = branch,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            // pull / push stat grid (draft 2-col)
            uiState.snapshot?.let { snapshot ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatBlock(
                        label = "pull",
                        value = "${snapshot.behind} commits",
                        modifier = Modifier.weight(1f)
                    )
                    StatBlock(
                        label = "push",
                        value = "${snapshot.ahead} commits",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    Button(
        onClick = onSyncNow,
        enabled = !uiState.isSyncRequestInFlight,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
            .height(52.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CloudSync,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.sync_now),
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun StatBlock(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ConfigCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            content()
        }
    }
}
