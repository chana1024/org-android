package com.orgutil.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.orgutil.domain.chat.AgentMode
import com.orgutil.domain.chat.ApprovalState
import com.orgutil.domain.chat.ChatMessageView
import com.orgutil.domain.chat.RiskLevel
import com.orgutil.ui.theme.OrgMono
import java.util.Date
import java.util.Locale
import com.orgutil.ui.viewmodel.ChatViewModel
import com.orgutil.ui.viewmodel.ChatUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    var inputText by remember { mutableStateOf("") }
    var showAutoArmDialog by remember { mutableStateOf(false) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.messages.size, uiState.liveAssistantText) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(index = uiState.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Chat", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Agent · org-mode",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showApiKeyDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = "LLM API Key",
                            tint = if (uiState.apiKeyConfigured) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // Draft 全自动 pill toggle in the top bar
                    val autoMode = uiState.mode == AgentMode.AUTO
                    Surface(
                        onClick = {
                            if (autoMode) {
                                viewModel.switchMode(AgentMode.APPROVAL, autoArmed = false)
                            } else {
                                showAutoArmDialog = true
                            }
                        },
                        shape = RoundedCornerShape(50),
                        color = if (autoMode) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (autoMode) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(
                                        if (autoMode) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline,
                                        androidx.compose.foundation.shape.CircleShape
                                    )
                            )
                            Text(
                                text = "全自动",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (autoMode) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
        if (uiState.mode == AgentMode.AUTO) {
            Text(
                text = "全自动模式：agent 将无确认、无限额地创建、修改、删除笔记，并可能提交并推送远端。删除不可恢复。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        uiState.error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        // ---- messages ----
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Draft thread timestamp chip
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, MaterialTheme.colorScheme.outlineVariant
                        )
                    ) {
                        Text(
                            text = remember {
                                java.text.SimpleDateFormat(
                                    "Today HH:mm", Locale.getDefault()
                                ).format(Date())
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
            items(uiState.messages, key = { it.id }) { message ->
                ChatMessageCard(message)
            }
            if (uiState.liveAssistantText.isNotEmpty()) {
                item {
                    ChatBubble(
                        text = uiState.liveAssistantText,
                        isUser = false,
                        isStreaming = true
                    )
                }
            }
        }

        // ---- pending approval card ----
        uiState.pendingApproval?.let { pending ->
            ApprovalCard(
                pending = pending,
                onApproveOnce = viewModel::approveOnce,
                onApproveSession = if (pending.sessionGrantAllowed) viewModel::approveSession else null,
                onDeny = viewModel::deny
            )
        }

        // ---- input row ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask about your notes…") },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (uiState.isRunning) {
                FilledIconButton(onClick = viewModel::stop) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                }
            } else {
                FilledIconButton(
                    onClick = {
                        viewModel.send(inputText)
                        inputText = ""
                    },
                    enabled = inputText.isNotBlank()
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
        }
        }
    }

    if (showAutoArmDialog) {
        AlertDialog(
            onDismissRequest = { showAutoArmDialog = false },
            title = { Text("启用全自动模式？") },
            text = {
                Text(
                    "agent 将不再逐次确认，也没有任何数量或大小限制：可以直接创建、修改、永久删除笔记，并可能自动 commit 并 push 远端。\n\n" +
                        "删除没有回收站，只能通过 git 历史找回（如果你开启过同步）。笔记内容中的注入指令无法被完全防御，请事后查看审计记录。"
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.switchMode(AgentMode.AUTO, autoArmed = true)
                    showAutoArmDialog = false
                }) { Text("启用全自动") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAutoArmDialog = false }) { Text("取消") }
            }
        )
    }

    if (showApiKeyDialog) {
        ApiKeyDialog(
            currentKey = "",
            onSave = { key ->
                viewModel.saveApiKey(key)
                showApiKeyDialog = false
            },
            onDismiss = { showApiKeyDialog = false }
        )
    }
}

@Composable
private fun ApiKeyDialog(currentKey: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf(currentKey) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("LLM API Key") },
        text = {
            Column {
                Text("Anthropic API key，存储在加密偏好中。")
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = key, onValueChange = { key = it }, singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = { if (key.isNotBlank()) onSave(key) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ApprovalCard(
    pending: com.orgutil.ui.viewmodel.PendingApprovalUi,
    onApproveOnce: () -> Unit,
    onApproveSession: (() -> Unit)?,
    onDeny: () -> Unit
) {
    // Draft approval card: error left border + CONFIRM REQUIRED chip
    val isHigh = pending.riskLevel == RiskLevel.HIGH
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            width = 3.dp,
            color = if (isHigh) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.tertiary
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "请求确认：${pending.toolName}",
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = OrgMono),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (isHigh) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                        )
                    ) {
                        Text(
                            text = "CONFIRM REQUIRED",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            if (isHigh) {
                Text(
                    text = "高风险操作，不可会话放行",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (pending.argsDigest.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(
                        text = pending.argsDigest,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = OrgMono),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onApproveOnce) { Text("仅本次允许") }
                onApproveSession?.let {
                    OutlinedButton(onClick = it) { Text("本会话允许") }
                }
                TextButton(onClick = onDeny) { Text("拒绝") }
            }
        }
    }
}

@Composable
private fun ChatMessageCard(message: ChatMessageView) {
    when (message.role) {
        "user" -> ChatBubble(text = message.content, isUser = true)
        "assistant" -> ChatBubble(text = message.content, isUser = false)
        "tool" -> ToolCallCard(message)
    }
}

@Composable
private fun ChatBubble(text: String, isUser: Boolean, isStreaming: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isUser) 20.dp else 6.dp,
                bottomEnd = if (isUser) 6.dp else 20.dp
            ),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(
                text = text + if (isStreaming) " ▌" else "",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun ToolCallCard(message: ChatMessageView) {
    val state = message.approvalState
    val failed = state == ApprovalState.DENIED || state == ApprovalState.VOIDED ||
        message.toolResultSummary?.startsWith("✗") == true
    // Draft tool card: teal left border (error when failed), mono title,
    // status pill, mono args block.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            width = 3.dp,
            color = if (failed) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "tool: ${message.toolName ?: "?"}",
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = OrgMono),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ToolStatusBadge(state = state, resultSummary = message.toolResultSummary)
                    }
                }
            }
            message.toolResultSummary?.let { summary ->
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = OrgMono),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolStatusBadge(state: ApprovalState?, resultSummary: String?) {
    val (icon, label) = when {
        state == ApprovalState.PENDING -> Icons.Outlined.HourglassTop to "等待确认"
        state == ApprovalState.DENIED -> Icons.Default.Block to "已拒绝"
        state == ApprovalState.VOIDED -> Icons.Default.Cancel to "已作废"
        state == ApprovalState.APPROVED && resultSummary == null -> Icons.Default.PlayArrow to "执行中"
        resultSummary?.startsWith("✗") == true -> Icons.Default.ErrorOutline to "失败"
        else -> Icons.Default.CheckCircle to "已执行"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}
