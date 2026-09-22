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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.orgutil.ui.viewmodel.ChatViewModel
import com.orgutil.ui.viewmodel.ChatUiState

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

    Column(modifier = Modifier.fillMaxSize()) {
        // ---- mode selector + AUTO banner ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                SegmentedButton(
                    selected = uiState.mode == AgentMode.APPROVAL,
                    onClick = { viewModel.switchMode(AgentMode.APPROVAL, autoArmed = false) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) { Text("审批") }
                SegmentedButton(
                    selected = uiState.mode == AgentMode.AUTO,
                    onClick = {
                        if (uiState.mode != AgentMode.AUTO) showAutoArmDialog = true
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) { Text("全自动") }
            }
            IconButton(onClick = { showApiKeyDialog = true }) {
                Text(
                    text = if (uiState.apiKeyConfigured) "🔑" else "🔑?",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

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
                maxLines = 4
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (uiState.isRunning) {
                IconButton(onClick = viewModel::stop) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                }
            } else {
                IconButton(
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (pending.riskLevel) {
                RiskLevel.HIGH -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.secondaryContainer
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "请求确认：${pending.toolName}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (pending.riskLevel == RiskLevel.HIGH) {
                Text(
                    text = "高风险操作，不可会话放行",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (pending.argsDigest.isNotBlank()) {
                Text(
                    text = pending.argsDigest,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDeny) { Text("拒绝") }
                onApproveSession?.let {
                    TextButton(onClick = it) { Text("本会话允许") }
                }
                Button(onClick = onApproveOnce) { Text("仅本次允许") }
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
            shape = RoundedCornerShape(12.dp),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(
                text = text + if (isStreaming) " ▌" else "",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(10.dp)
            )
        }
    }
}

@Composable
private fun ToolCallCard(message: ChatMessageView) {
    val state = message.approvalState
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                state == ApprovalState.DENIED || state == ApprovalState.VOIDED ->
                    MaterialTheme.colorScheme.errorContainer
                message.toolResultSummary?.startsWith("✗") == true ->
                    MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = toolBadge(state, message.toolResultSummary),
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = message.toolName ?: "tool",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
            }
            message.toolResultSummary?.let { summary ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = summary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun toolBadge(state: ApprovalState?, resultSummary: String?): String = when {
    state == ApprovalState.PENDING -> "⏳ 等待确认"
    state == ApprovalState.DENIED -> "🚫 已拒绝"
    state == ApprovalState.VOIDED -> "✖ 已作废"
    state == ApprovalState.APPROVED && resultSummary == null -> "▶ 执行中"
    resultSummary?.startsWith("✗") == true -> "✗ 失败"
    else -> "✅ 已执行"
}
