package com.orgutil.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.orgutil.domain.chat.AgentMode
import com.orgutil.domain.chat.AgendaContextReference
import com.orgutil.domain.chat.ApprovalState
import com.orgutil.domain.chat.ChatMessageView
import com.orgutil.domain.chat.RiskLevel
import com.orgutil.domain.chat.skills.ChatSkillRegistry
import com.orgutil.domain.chat.skills.SessionCommands
import com.orgutil.domain.chat.skills.SkillDefinition
import com.orgutil.data.agent.ProviderKind
import com.orgutil.data.agent.ProviderType
import com.orgutil.data.agent.WebSearchSupport
import com.orgutil.ui.components.OrgTopBar
import com.orgutil.ui.components.OrgTopBarIcon
import com.orgutil.ui.components.OrgMonoChip
import com.orgutil.ui.components.AssistantMarkdown
import com.orgutil.ui.components.openChatLink
import com.orgutil.ui.theme.OrgMono
import java.util.Date
import java.util.Locale
import com.orgutil.ui.viewmodel.ChatViewModel
import com.orgutil.ui.viewmodel.ChatUiState
import com.orgutil.ui.viewmodel.AgendaContextOption
import com.orgutil.ui.viewmodel.ProfileSaveOutcome
import com.orgutil.ui.viewmodel.ProviderProfileDraft
import com.orgutil.ui.viewmodel.ProviderProfileUi
import com.orgutil.ui.viewmodel.SessionListItem
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    var inputText by remember { mutableStateOf("") }
    var showAutoArmDialog by remember { mutableStateOf(false) }
    var showProviderDialog by remember { mutableStateOf(false) }
    var showSessionsDialog by remember { mutableStateOf(false) }
    var showAgendaReferencePicker by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SessionListItem?>(null) }
    var deleteTarget by remember { mutableStateOf<SessionListItem?>(null) }
    var editingProfile by remember { mutableStateOf<ProviderProfileDraft?>(null) }
    var deleteProfileTarget by remember { mutableStateOf<ProviderProfileUi?>(null) }
    // Composer draft state: the selected slash skill lives as a removable
    // chip, the editor only ever holds arguments/prose. Send reconstitutes
    // the canonical "/<command> args" text for the ViewModel.
    var selectedSkill by remember { mutableStateOf<SkillDefinition?>(null) }
    var skillPanelOpen by remember { mutableStateOf(false) }
    // Presentation grouping: user/assistant bubbles stay individual, runs of
    // consecutive tool messages collapse into one live-count summary row.
    val groupedItems = remember(uiState.messages) { groupChatItems(uiState.messages) }

    // ---- session-open initial placement: open at the LATEST messages ----
    // The transcript arrives asynchronously (activateSession emits an empty
    // list, the DB flow populates it afterwards), so the position cannot be
    // baked into the state at creation time up front. Instead the list state
    // is recreated at the exact composition where a session's transcript
    // first becomes renderable, with its initial index anchored on the last
    // renderable row: the FIRST painted frame of the populated list is
    // already at the bottom — no top-of-conversation frame, no animated
    // open-scroll. One placement per session activation (quick switches each
    // get their own state), so a previous session's position can never leak
    // into the next one. Both markers are saveable: rotation/process death
    // must restore the reader's position, not re-place to the bottom.
    var placedSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var placementEpoch by rememberSaveable { mutableStateOf(0) }
    val activeSessionId = uiState.sessionId
    val placementNow = activeSessionId != null &&
        groupedItems.isNotEmpty() &&
        placedSessionId != activeSessionId
    if (placementNow) {
        placedSessionId = activeSessionId
        placementEpoch += 1
    }
    val listState = rememberSaveable(placementEpoch, saver = LazyListState.Saver) {
        // Same last-row arithmetic as the follow-latest effect below: the
        // timestamp header occupies index 0, grouped rows follow, and the
        // optional live-bubble / waiting-dot rows extend the tail. The
        // factory runs only when the epoch changes (i.e. in the composition
        // that just received this session's content), so the captured
        // groupedItems/uiState are that emission's real values.
        var initialIndex = groupedItems.size // header item occupies index 0
        if (uiState.liveAssistantText.isNotBlank()) initialIndex += 1
        if (uiState.isRunning && uiState.liveAssistantText.isBlank()) initialIndex += 1
        LazyListState(
            firstVisibleItemIndex = initialIndex
                .coerceIn(0, groupedItems.size + 2)
        )
    }
    // Open tool-details sheet: tracked by the group's stable key (its first
    // call id) and re-resolved against groupedItems on every emission, so
    // calls appended to the same run join the open sheet and status/result
    // updates keep flowing in. It closes when the group disappears from the
    // transcript or the session switches — not on ordinary refreshes.
    var openToolGroupKey by remember { mutableStateOf<String?>(null) }
    val openToolCalls = openToolGroupKey?.let { key ->
        groupedItems.filterIsInstance<ChatListItem.ToolCalls>()
            .firstOrNull { it.key == key }?.calls
    }
    LaunchedEffect(uiState.sessionId) { openToolGroupKey = null }

    // Finish the initial placement once the placed content is measured: the
    // anchor index top-aligns the last row, which for a newest reply TALLER
    // than the viewport leaves that reply's bottom edge off-screen. One
    // non-animated snap to the absolute end of the composed list pins the
    // last message's bottom edge; when the last row is shorter than the
    // viewport the anchor already clamps to max scroll and this is a visual
    // no-op. snappedEpoch makes it once per placement epoch across
    // recomposition AND restoration — a rotated/restored reader keeps the
    // saved position instead of being pulled to the bottom again.
    var snappedEpoch by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(placementEpoch) {
        if (placementEpoch == 0 || snappedEpoch == placementEpoch) return@LaunchedEffect
        // The freshly recreated state carries no measurements yet; wait for
        // the placed transcript to actually be laid out so the snap targets
        // the real end of the composed list (fires immediately when layout
        // has already happened).
        snapshotFlow { listState.layoutInfo.totalItemsCount }
            .first { it > 1 }
        snapToLastItemEdge(listState)
        snappedEpoch = placementEpoch
    }

    // Scroll to the *actual* last LazyColumn item: the timestamp header, the
    // grouped transcript, and the optional live-bubble / waiting-dot rows all
    // occupy indices, so messages.size alone is never the last index. During
    // an active run we snap (streaming deltas fire constantly); a finished
    // append animates so the sent message and reply land in view.
    LaunchedEffect(
        uiState.messages.size,
        uiState.messages.lastOrNull()?.id,
        uiState.liveAssistantText,
        uiState.isRunning,
        placementEpoch
    ) {
        // The session-open emission belongs to the initial placement above:
        // the list is already anchored at the end, and animating here would
        // scroll (upward, for a tall last message) exactly when the open
        // must hold still. Later emissions of the same session run normally.
        if (placementNow) return@LaunchedEffect
        if (uiState.messages.isEmpty() && !uiState.isRunning) return@LaunchedEffect
        var lastIndex = groupedItems.size // header item occupies index 0
        if (uiState.liveAssistantText.isNotBlank()) lastIndex += 1
        if (uiState.isRunning && uiState.liveAssistantText.isBlank()) lastIndex += 1
        // Blank-assistant filtering and the live-bubble rules above change the
        // renderable row count; every contributor comes from this same
        // uiState snapshot, and the +2 bound (live bubble + waiting dots) can
        // never exceed the composed list, so the index stays in range.
        lastIndex = lastIndex.coerceAtMost(groupedItems.size + 2)
        if (uiState.isRunning || uiState.liveAssistantText.isNotBlank()) {
            listState.scrollToItem(lastIndex)
        } else {
            listState.animateScrollToItem(lastIndex)
        }
    }

    // A failed /org send-time capture hands the draft back to the composer:
    // re-split the canonical text so the skill chip and the arguments come
    // back exactly as they were composed.
    LaunchedEffect(uiState.pendingDraft) {
        uiState.pendingDraft?.let { draft ->
            val parsed = parseLeadingSkill(draft)
            if (parsed != null) {
                selectedSkill = parsed.first
                inputText = parsed.second
            } else {
                inputText = draft
            }
            viewModel.consumePendingDraft()
        }
    }

    // The selected skill is draft authority of THIS session - the chip (and
    // the /org authority it transmits) must never leak across a switch.
    LaunchedEffect(uiState.sessionId) {
        selectedSkill = null
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            OrgTopBar(
                title = "Chat",
                subtitle = {
                    Text(
                        text = uiState.sessionTitle,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                },
                actions = {
                    IconButton(onClick = { showSessionsDialog = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Chat,
                            contentDescription = "Sessions",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showProviderDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = "模型供应商设置",
                            tint = if (uiState.providerReady) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
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

        // ---- interrupted-run recovery banner ----
        uiState.resumeNotice?.let { notice ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = "上次任务被中断",
                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = OrgMono),
                        fontWeight = FontWeight.SemiBold
                    )
                    notice.prompt?.let { prompt ->
                        Text(
                            text = "「" + prompt.take(80) + "」",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    if (notice.unknownEffectTools.isNotEmpty()) {
                        Text(
                            text = "⚠ 以下操作在中断时执行状态未知（可能已生效也可能未生效，请先确认文件/git 状态）：\n" +
                                notice.unknownEffectTools.joinToString("\n") { "· $it" },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = OrgMono),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (notice.pendingApprovals > 0) {
                        Text(
                            text = "· ${notice.pendingApprovals} 个等待中的审批已作废",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Button(onClick = viewModel::resumeRun, enabled = !uiState.isRunning) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("继续任务")
                        }
                        OutlinedButton(onClick = viewModel::discardInterruptedRun) { Text("放弃") }
                    }
                }
            }
        }

        // ---- context budget status ----
        uiState.compactionStatus?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
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
                                    "'Today' HH:mm", Locale.getDefault()
                                ).format(Date())
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
            items(groupedItems, key = { it.key }) { item ->
                when (item) {
                    is ChatListItem.Single -> ChatMessageCard(item.message)
                    is ChatListItem.ToolCalls -> ToolActivitySummaryRow(
                        calls = item.calls,
                        agentRunning = uiState.isRunning,
                        onClick = { openToolGroupKey = item.key }
                    )
                }
            }
            // Whitespace-only live text renders nothing — same isBlank rule
            // as the persisted blank-assistant filter in groupChatItems.
            if (uiState.liveAssistantText.isNotBlank()) {
                item {
                    ChatBubble(
                        text = uiState.liveAssistantText,
                        isUser = false,
                        isStreaming = true
                    )
                }
            }
            // Waiting indicator (ask 5): lightweight pulsing dots while a run
            // is in flight and nothing has streamed yet. It disappears as
            // soon as the first token arrives and never touches the streamed
            // bubble or the stop control in the input row.
            if (uiState.isRunning && uiState.liveAssistantText.isBlank()) {
                item(key = "waiting-dots") {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        WaitingDots()
                    }
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

        // ---- composer ----
        // One integrated rounded surface: a single row with the compact plus
        // menu (left), borderless BasicTextField (center) and send/stop
        // (right); the context chip strip renders above it only when a skill
        // or reference is selected. The slash command is a chip, not visible
        // text - send reconstitutes the canonical text.
        val composerFocus = remember { FocusRequester() }
        var editorHasFocus by remember { mutableStateOf(false) }
        var toolbarMenuOpen by remember { mutableStateOf(false) }
        val slashSuggestions = remember(inputText) { slashSuggestionsFor(inputText) }
        if (skillPanelOpen || slashSuggestions.isNotEmpty()) {
            SlashSuggestionPanel(
                entries = slashSuggestions.ifEmpty { slashMenuEntries() },
                showClose = skillPanelOpen && slashSuggestions.isEmpty(),
                onPick = { entry ->
                    val skill = entry.skill
                    if (skill != null) {
                        // Strip ONLY a typed leading "/" token. Ordinary prose
                        // (panel opened from the toolbar menu) is kept verbatim
                        // as the skill's arguments - switching/choosing a skill
                        // must never eat the first word of the draft.
                        val draft = inputText.trim()
                        val remaining = if (draft.startsWith("/")) {
                            draft.replaceFirst("^\\S+".toRegex(), "").trim()
                        } else {
                            inputText
                        }
                        selectedSkill = skill
                        inputText = remaining
                    } else {
                        // Local session command (/fork): becomes visible
                        // draft text and executes on send - never a chip,
                        // and the ViewModel intercepts it before any model
                        // call.
                        inputText = entry.command
                    }
                    skillPanelOpen = false
                    composerFocus.requestFocus()
                },
                onClose = { skillPanelOpen = false }
            )
        }
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (editorHasFocus) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column {
                if (selectedSkill != null || uiState.selectedAgendaReferences.isNotEmpty()) {
                    // Context strip renders only when something is selected:
                    // one compact horizontally scrollable row of chips, no
                    // reserved empty chip area in the plain composer.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(start = 8.dp, end = 8.dp, top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        selectedSkill?.let { skill ->
                            ComposerSkillChip(
                                label = "${skill.command} ${skill.name}",
                                onRemove = { selectedSkill = null }
                            )
                        }
                        uiState.selectedAgendaReferences.forEach { reference ->
                            ComposerReferenceChip(
                                label = "${reference.todo?.let { "$it " } ?: ""}${reference.title}",
                                onRemove = { viewModel.removeAgendaContext(reference.id) }
                            )
                        }
                    }
                }
                // Integrated single row: compact plus/menu left, borderless
                // BasicTextField center (weight keeps the icons off the typed
                // text on narrow screens), send/stop right. BasicTextField
                // drops TextField's default minimum height and inner padding,
                // so an empty composer is one ~52-56dp bar; the field grows
                // with the draft up to 5 lines and scrolls internally beyond.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(start = 2.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
                ) {
                    Box {
                        IconButton(
                            onClick = { toolbarMenuOpen = true },
                            enabled = !uiState.isRunning,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "添加引用或选择技能",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(
                            expanded = toolbarMenuOpen,
                            onDismissRequest = { toolbarMenuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (uiState.selectedAgendaReferences.isEmpty()) "引用 Agenda 条目"
                                        else "引用 Agenda 条目（${uiState.selectedAgendaReferences.size}）"
                                    )
                                },
                                onClick = {
                                    toolbarMenuOpen = false
                                    viewModel.loadAgendaContextOptions()
                                    showAgendaReferencePicker = true
                                },
                                enabled = !uiState.isRunning,
                                leadingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.EventNote,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("选择技能") },
                                onClick = {
                                    toolbarMenuOpen = false
                                    skillPanelOpen = true
                                },
                                enabled = !uiState.isRunning,
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Bolt,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )
                        }
                    }
                    BasicTextField(
                        value = inputText,
                        onValueChange = { raw ->
                            // Typing/pasting an exact leading command token (even
                            // with multiline arguments behind it) resolves into
                            // chip + prose; unknown "/" text stays as typed for
                            // the ViewModel to reject visibly.
                            val parsed = parseLeadingSkill(raw)
                            if (parsed != null) {
                                selectedSkill = parsed.first
                                inputText = parsed.second
                            } else {
                                inputText = raw
                            }
                        },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 5,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(composerFocus)
                            .onFocusChanged { editorHasFocus = it.isFocused }
                            .heightIn(min = 32.dp)
                            .padding(horizontal = 6.dp),
                        decorationBox = { innerField ->
                            Box(modifier = Modifier.fillMaxWidth()) {
                                if (inputText.isEmpty()) {
                                    Text(
                                        text = "输入消息…",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                innerField()
                            }
                        }
                    )
                    if (uiState.isRunning) {
                        FilledIconButton(
                            onClick = viewModel::stop,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "停止")
                        }
                    } else {
                        FilledIconButton(
                            onClick = {
                                val skill = selectedSkill
                                val args = inputText.trim()
                                val canonical = if (skill == null) {
                                    inputText
                                } else {
                                    skill.command + if (args.isEmpty()) "" else " $args"
                                }
                                if (viewModel.send(canonical)) {
                                    inputText = ""
                                    selectedSkill = null
                                }
                            },
                            enabled = (inputText.isNotBlank() || selectedSkill != null) &&
                                uiState.loadingAgendaReferenceIds.isEmpty(),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "发送")
                        }
                    }
                }
            }
        }
        }
    }

    if (showAgendaReferencePicker) {
        AgendaContextPickerDialog(
            uiState = uiState,
            onToggle = viewModel::toggleAgendaContext,
            onDismiss = { showAgendaReferencePicker = false }
        )
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

    // Tool-call details sheet: every call of the tapped group with friendly
    // name, status, arguments and result. Calls come from the live group
    // resolution above, so pending → running → finished transitions and
    // newly appended calls keep updating while the sheet is open.
    openToolCalls?.takeIf { it.isNotEmpty() }?.let { calls ->
        ToolCallsDetailSheet(
            calls = calls,
            agentRunning = uiState.isRunning,
            onDismiss = { openToolGroupKey = null }
        )
    }

    if (showProviderDialog) {
        ProviderConfigDialog(
            uiState = uiState,
            onEdit = {
                viewModel.clearProviderNotice()
                editingProfile = it
            },
            onDelete = { deleteProfileTarget = it },
            onSetDefault = viewModel::setDefaultProfile,
            onSetSessionProfile = viewModel::setSessionProfile,
            onSetFallback = viewModel::setFallbackProfile,
            onBudgetChange = viewModel::setBudgetTokens,
            onNew = {
                viewModel.clearProviderNotice()
                editingProfile = ProviderProfileDraft()
            },
            onDismiss = { showProviderDialog = false }
        )
    }

    editingProfile?.let { draft ->
        ProfileEditorDialog(
            draft = draft,
            // The dialog closes itself only when the VM reports success;
            // on failure it keeps the typed draft (key included) on screen.
            onSave = viewModel::saveProfile,
            onDismiss = { editingProfile = null }
        )
    }

    deleteProfileTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteProfileTarget = null },
            title = { Text("删除供应商配置？") },
            text = { Text("配置「${target.name}」将被删除；使用它的会话将回退到默认配置。已存密钥同时清除。") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteProfile(target.id)
                        deleteProfileTarget = null
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("删除") }
            },
            dismissButton = { OutlinedButton(onClick = { deleteProfileTarget = null }) { Text("取消") } }
        )
    }

    if (showSessionsDialog) {
        SessionsDialog(
            sessions = uiState.sessions,
            onSwitch = { session ->
                viewModel.switchSession(session.id)
                showSessionsDialog = false
            },
            onNew = {
                viewModel.newSession()
                showSessionsDialog = false
            },
            onRename = { renameTarget = it },
            onDelete = { deleteTarget = it },
            onDismiss = { showSessionsDialog = false }
        )
    }

    renameTarget?.let { target ->
        RenameSessionDialog(
            currentTitle = target.title,
            onSave = { title ->
                viewModel.renameSession(target.id, title)
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除会话？") },
            text = {
                Text("会话「${target.title}」的全部消息、工具审计记录和压缩摘要将被永久删除，无法恢复。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteSession(target.id)
                        deleteTarget = null
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("删除") }
            },
            dismissButton = {
                OutlinedButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SessionsDialog(
    sessions: List<SessionListItem>,
    onSwitch: (SessionListItem) -> Unit,
    onNew: () -> Unit,
    onRename: (SessionListItem) -> Unit,
    onDelete: (SessionListItem) -> Unit,
    onDismiss: () -> Unit
) {
    val fullFormat = remember { java.text.SimpleDateFormat("yy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val todayFormat = remember { java.text.SimpleDateFormat("今天 HH:mm:ss", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("会话") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (sessions.isEmpty()) {
                    Text("暂无会话", style = MaterialTheme.typography.bodyMedium)
                }
                sessions.forEach { session ->
                    Surface(
                        onClick = { onSwitch(session) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (session.isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (session.isActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = session.title.ifBlank { "(未命名)" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (session.isActive) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (session.isActive) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Text(
                                    // Order in the list is most-recent-activity desc
                                    // (deterministic tie breaker); the timestamp shown
                                    // is the session's own metadata - seconds included
                                    // so same-minute sessions stay distinguishable.
                                    text = buildString {
                                        append(formatSessionTime(session.updatedAt, fullFormat, todayFormat))
                                        append(" · ").append(session.messageCount).append(" 条")
                                        when (session.runStatus) {
                                            "RUNNING" -> append(" · 运行中")
                                            "AWAITING_APPROVAL" -> append(" · 等待批准")
                                            "INTERRUPTED" -> append(" · 已中断")
                                        }
                                        if (session.isActive) append(" · 当前")
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                session.preview?.let { preview ->
                                    Text(
                                        // First line only keeps the row compact; the
                                        // preview is pre-trimmed so this is never blank.
                                        text = preview.lineSequence().first().take(80),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                            IconButton(onClick = { onRename(session) }) {
                                Icon(
                                    Icons.Default.Edit, contentDescription = "重命名",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { onDelete(session) }) {
                                Icon(
                                    Icons.Default.Delete, contentDescription = "删除",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onNew) { Text("新建会话") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/**
 * Session list timestamps: a missing/zero legacy value is shown honestly as
 * 时间未知 (never a fabricated 1970 date); today's sessions get a same-day
 * label, everything else the full date with seconds so entries touched within
 * one minute remain distinguishable.
 */
private fun formatSessionTime(
    updatedAt: Long?,
    fullFormat: java.text.SimpleDateFormat,
    todayFormat: java.text.SimpleDateFormat
): String {
    val ts = updatedAt ?: return "时间未知"
    val day = java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    return if (day == java.time.LocalDate.now()) todayFormat.format(Date(ts)) else fullFormat.format(Date(ts))
}

@Composable
private fun RenameSessionDialog(
    currentTitle: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(currentTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名会话") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text("会话名称") }
            )
        },
        confirmButton = {
            Button(onClick = { if (title.isNotBlank()) onSave(title) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun ProviderConfigDialog(
    uiState: ChatUiState,
    onEdit: (ProviderProfileDraft) -> Unit,
    onDelete: (ProviderProfileUi) -> Unit,
    onSetDefault: (String) -> Unit,
    onSetSessionProfile: (String?) -> Unit,
    onSetFallback: (String?) -> Unit,
    onBudgetChange: (Long) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit
) {
    var budgetText by remember(uiState.budgetTokens) { mutableStateOf(uiState.budgetTokens.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("模型供应商") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "当前：" + (uiState.activeProfileName ?: "未配置") +
                        (uiState.fallbackProfileName?.let { " · 备用：$it" } ?: ""),
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = OrgMono),
                    color = if (uiState.providerReady) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                // Save/edit outcome surfaced INSIDE the dialog (the main-screen
                // banner sits behind this modal and would be invisible).
                uiState.providerNotice?.let { notice ->
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (uiState.providerNoticeIsError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }
                if (uiState.profiles.isEmpty()) {
                    Text(
                        "尚未配置任何供应商。新建一个配置（Anthropic 或 OpenAI 兼容入口），密钥仅存于本机加密存储。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                uiState.profiles.forEach { profile ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (profile.isSessionProfile) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (profile.isSessionProfile) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                if (profile.isDefault) OrgMonoChip("默认")
                                if (profile.isFallback) OrgMonoChip("备用")
                                if (!profile.hasKey) OrgMonoChip("无密钥")
                            }
                            Text(
                                text = (when (profile.type) {
                                    "OPENAI_COMPATIBLE" -> "Chat 补全"
                                    "RESPONSES" -> "Responses"
                                    else -> "Anthropic"
                                }) + " · " + profile.model +
                                    (profile.webSearchLabel?.let { " · 搜索:$it" } ?: ""),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = profile.baseUrl,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                TextButton(onClick = {
                                    onEdit(
                                        ProviderProfileDraft(
                                            id = profile.id, name = profile.name, type = profile.type,
                                            baseUrl = profile.baseUrl, model = profile.model,
                                            webSearchEnabled = profile.webSearchEnabled,
                                            searchSupportOverride = profile.webSearchOverride
                                        )
                                    )
                                }) { Text("编辑", style = MaterialTheme.typography.labelMedium) }
                                if (!profile.isDefault) {
                                    TextButton(onClick = { onSetDefault(profile.id) }) {
                                        Text("设默认", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                TextButton(onClick = { onSetSessionProfile(profile.id) }) {
                                    Text("本会话", style = MaterialTheme.typography.labelMedium)
                                }
                                TextButton(onClick = { onSetFallback(if (profile.isFallback) null else profile.id) }) {
                                    Text(
                                        if (profile.isFallback) "撤备用" else "设备用",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                                TextButton(onClick = { onDelete(profile) }) {
                                    Text("删除", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
                if (uiState.profiles.any { it.isSessionProfile }) {
                    TextButton(onClick = { onSetSessionProfile(null) }) { Text("本会话改回默认配置") }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text("上下文预算（tokens，估算阈值）", style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = budgetText,
                        onValueChange = { budgetText = it.filter { c -> c.isDigit() }.take(7) },
                        singleLine = true,
                        modifier = Modifier.width(120.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = OrgMono)
                    )
                    TextButton(onClick = { budgetText.toLongOrNull()?.let(onBudgetChange) }) { Text("应用") }
                }
                Text(
                    "超过预算 70% 时，较早对话被摘要为持久摘要；近期完整对话与未决调用保留，完整历史始终保留在会话记录中。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onNew) { Text("新建配置") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun ProfileEditorDialog(
    draft: ProviderProfileDraft,
    onSave: (ProviderProfileDraft, (ProfileSaveOutcome) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(draft.name) }
    var type by remember { mutableStateOf(draft.type) }
    var baseUrl by remember { mutableStateOf(draft.baseUrl) }
    var model by remember { mutableStateOf(draft.model) }
    var apiKey by remember { mutableStateOf(draft.apiKey) }
    var webSearch by remember { mutableStateOf(draft.webSearchEnabled) }
    var searchOverride by remember { mutableStateOf(draft.searchSupportOverride) }
    // Save-in-flight guard: repeated taps cannot stack saves.
    var saving by remember { mutableStateOf(false) }
    // Set when a save failed: shown in-editor; the typed draft is kept.
    var saveError by remember { mutableStateOf<String?>(null) }
    // Row identity for this editor instance. Normally draft.id; after a
    // PARTIAL save (row durable, later step failed) it holds the persisted
    // id so a retry updates that row instead of inserting a duplicate.
    var workingId by remember { mutableStateOf(draft.id) }
    // Validation stays silent until the first 保存 attempt, then reacts
    // live: fixing a field clears its error immediately.
    var showValidation by remember { mutableStateOf(false) }
    val nameBlank = name.isBlank()
    val urlBlank = baseUrl.isBlank()
    val modelBlank = model.isBlank()
    val keyMissing = workingId == null && apiKey.isBlank()
    val openAi = type == "OPENAI_COMPATIBLE"
    val responses = type == "RESPONSES"
    // Honest capability for the CURRENT draft (URL + protocol + override),
    // recomputed live while editing - same resolver the registry uses.
    val support = remember(type, baseUrl, searchOverride) {
        runCatching {
            WebSearchSupport.resolve(
                runCatching { ProviderType.valueOf(type) }.getOrDefault(ProviderType.ANTHROPIC),
                baseUrl,
                ProviderKind.AUTO,
                if (searchOverride) WebSearchSupport.OVERRIDE_SUPPORTED else null
            )
        }.getOrNull()
    }
    AlertDialog(
        // Back/outside-tap while a save is in flight must not throw away the
        // typed draft; the outcome callback decides when closing is safe.
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (draft.id == null) "新建供应商配置" else "编辑供应商配置") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !openAi && !responses,
                        onClick = {
                            type = "ANTHROPIC"
                            baseUrl = "https://api.anthropic.com"
                        },
                        shape = SegmentedButtonDefaults.itemShape(0, 3)
                    ) { Text("Anthropic", style = MaterialTheme.typography.labelSmall) }
                    SegmentedButton(
                        selected = openAi,
                        onClick = {
                            type = "OPENAI_COMPATIBLE"
                            baseUrl = "https://api.openai.com/v1"
                        },
                        shape = SegmentedButtonDefaults.itemShape(1, 3)
                    ) { Text("Chat", style = MaterialTheme.typography.labelSmall) }
                    SegmentedButton(
                        selected = responses,
                        onClick = {
                            type = "RESPONSES"
                            baseUrl = "https://api.openai.com/v1"
                        },
                        shape = SegmentedButtonDefaults.itemShape(2, 3)
                    ) { Text("Responses", style = MaterialTheme.typography.labelSmall) }
                }
                // Compact presets: pin protocol + base URL + a model hint.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    profilePreset("OpenAI") { type = "RESPONSES"; baseUrl = "https://api.openai.com/v1" }
                    profilePreset("GLM 对话") { type = "OPENAI_COMPATIBLE"; baseUrl = "https://open.bigmodel.cn/api/paas/v4" }
                    profilePreset("GLM Responses") { type = "RESPONSES"; baseUrl = "https://open.bigmodel.cn/api/v1" }
                    profilePreset("DeepSeek") { type = "ANTHROPIC"; baseUrl = "https://api.deepseek.com/anthropic" }
                    profilePreset("Anthropic") { type = "ANTHROPIC"; baseUrl = "https://api.anthropic.com" }
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("名称") },
                    isError = showValidation && nameBlank,
                    supportingText = {
                        if (showValidation && nameBlank) Text("名称不能为空", color = MaterialTheme.colorScheme.error)
                    }
                )
                OutlinedTextField(
                    value = baseUrl, onValueChange = { baseUrl = it }, singleLine = true,
                    label = {
                        Text(
                            when {
                                openAi -> "Base URL（如 https://host/v1）"
                                responses -> "Base URL（自动补全 /responses）"
                                else -> "Base URL（自动补全 /v1/messages）"
                            }
                        )
                    },
                    isError = showValidation && urlBlank,
                    supportingText = {
                        if (showValidation && urlBlank) {
                            Text("Base URL 不能为空：请填写或点上方预设", color = MaterialTheme.colorScheme.error)
                        }
                    }
                )
                OutlinedTextField(
                    value = model, onValueChange = { model = it }, singleLine = true,
                    label = { Text("模型 ID") },
                    isError = showValidation && modelBlank,
                    supportingText = {
                        if (showValidation && modelBlank) Text("模型 ID 不能为空", color = MaterialTheme.colorScheme.error)
                    }
                )
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it }, singleLine = true,
                    label = { Text(if (workingId == null) "API Key" else "API Key（留空保持不变）") },
                    isError = showValidation && keyMissing,
                    supportingText = {
                        if (showValidation && keyMissing) {
                            Text("新建配置需要填写 API Key（编辑时留空则保留已存密钥）", color = MaterialTheme.colorScheme.error)
                        }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                // ---- provider-native web search (one compact switch row) ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("联网搜索（供应商托管）", style = MaterialTheme.typography.bodyMedium)
                        support?.let {
                            Text(
                                text = "搜索能力：${it.label} · ${it.reason}",
                                style = MaterialTheme.typography.labelSmall,
                                color = when (it) {
                                    is WebSearchSupport.Supported -> MaterialTheme.colorScheme.primary
                                    is WebSearchSupport.Unverified -> MaterialTheme.colorScheme.tertiary
                                    is WebSearchSupport.Unsupported -> MaterialTheme.colorScheme.error
                                }
                            )
                        }
                    }
                    Switch(
                        checked = webSearch,
                        onCheckedChange = { enabled ->
                            // A documented-ignored route stays off; the reason
                            // line explains why instead of silently failing.
                            if (enabled && support is WebSearchSupport.Unsupported) return@Switch
                            webSearch = enabled
                        },
                        enabled = support !is WebSearchSupport.Unsupported
                    )
                }
                if (support is WebSearchSupport.Unverified && webSearch) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = searchOverride, onCheckedChange = { searchOverride = it })
                        Text(
                            "该线路托管搜索未获官方文档确认，仍发送搜索工具（自定义网关已验证可用时勾选）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Text(
                    text = when {
                        openAi -> "OpenAI 兼容入口使用 Chat Completions SSE；URL 自动补全 /v1/chat/completions。"
                        responses -> "Responses 协议（OpenAI / GLM 原生 / DeepSeek）；URL 自动补全 /responses。"
                        else -> "Anthropic Messages SSE；默认 https://api.anthropic.com。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Persistence failure surfaced where the draft still lives, so
                // the retry path (same fields, same key, same row id) is one
                // tap away. Static text only — never raw exception payloads.
                saveError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving,
                onClick = {
                    // Invalid input is explained in-place, never a silent no-op
                    // (the old dead-button behavior read as "Save is broken").
                    if (nameBlank || urlBlank || modelBlank || keyMissing) {
                        showValidation = true
                        return@Button
                    }
                    saving = true
                    saveError = null
                    onSave(
                        ProviderProfileDraft(
                            id = workingId, name = name, type = type,
                            baseUrl = baseUrl, model = model, apiKey = apiKey,
                            webSearchEnabled = webSearch,
                            searchSupportOverride = searchOverride && webSearch
                        )
                    ) { outcome ->
                        // Adopt the durable row id even on failure so the
                        // next attempt updates it instead of duplicating.
                        outcome.id?.let { workingId = it }
                        if (outcome.success) {
                            onDismiss() // success-only close; notice shows behind
                        } else {
                            saving = false
                            saveError = "保存失败：配置未完整写入本地存储。" +
                                "已填写的内容（含密钥）保留在本窗口，可直接重试。"
                        }
                    }
                }
            ) { Text(if (saving) "保存中…" else "保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(if (saving) "保存中…" else "取消") }
        }
    )
}

@Composable
private fun profilePreset(label: String, onPick: () -> Unit) {
    AssistChip(onClick = onPick, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
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

/** One renderable transcript row: a bubble, or a collapsed tool-call group. */
private sealed interface ChatListItem {
    val key: String

    data class Single(val message: ChatMessageView) : ChatListItem {
        override val key: String get() = message.id
    }

    data class ToolCalls(val calls: List<ChatMessageView>) : ChatListItem {
        // Keyed by the group's first call: appending later calls to the same
        // agent turn keeps the row identity (and its in-place count updates)
        // stable instead of duplicating summary rows.
        override val key: String get() = "toolgroup-${calls.first().id}"
    }
}

/**
 * One activity row per user turn: every tool call of the turn — across all
 * model/tool iterations, so blank assistant records in between never split
 * the statistics — collapses into a single ToolCalls item placed at the
 * turn's first tool position, keeping the row's index and key stable while
 * later rounds append calls. A user prompt closes the previous turn, and
 * tool history before the first prompt forms one bounded leading group;
 * calls never merge across prompts or sessions. Blank assistant records are
 * hidden from rendering only (persistence keeps them for toolUses pairing,
 * resume and audit); substantive assistant text keeps its chronology.
 */
private fun groupChatItems(messages: List<ChatMessageView>): List<ChatListItem> {
    val rendered = messages.filterNot { it.role == "assistant" && it.content.isBlank() }
    val items = mutableListOf<ChatListItem>()
    var calls = mutableListOf<ChatMessageView>()
    var groupIndex = -1 // items index of the current turn's ToolCalls row
    rendered.forEach { message ->
        when (message.role) {
            "user" -> {
                calls = mutableListOf()
                groupIndex = -1
                items += ChatListItem.Single(message)
            }
            "tool" -> {
                calls += message
                if (groupIndex < 0) {
                    items += ChatListItem.ToolCalls(calls.toList())
                    groupIndex = items.lastIndex
                } else {
                    items[groupIndex] = ChatListItem.ToolCalls(calls.toList())
                }
            }
            else -> items += ChatListItem.Single(message)
        }
    }
    return items
}

/**
 * Non-animated end-of-list snap for the session-open placement. The initial
 * anchor index top-aligns the last row; a last message taller than the
 * viewport needs the absolute end of the list (its bottom edge) instead.
 * scrollToItem on the last valid index, then a bounded forward walk to
 * exhaust any remaining scrollable distance — deterministic regardless of
 * how tall the last row is, and always terminating (guard-bounded).
 */
private suspend fun snapToLastItemEdge(listState: LazyListState) {
    val info = listState.layoutInfo
    if (info.totalItemsCount == 0) return
    listState.scrollToItem(info.totalItemsCount - 1)
    var guard = 0
    while (listState.canScrollForward && guard++ < 16) {
        val viewport = (listState.layoutInfo.viewportEndOffset -
            listState.layoutInfo.viewportStartOffset).coerceAtLeast(1)
        listState.scrollBy(viewport.toFloat())
    }
}

@Composable
private fun ChatMessageCard(message: ChatMessageView) {
    when (message.role) {
        "user" -> Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.fillMaxWidth()
        ) {
            message.skillId?.let { skillId ->
                ChatSkillRegistry.byId(skillId)?.let { skill ->
                    Surface(
                        onClick = {},
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.padding(end = 4.dp, bottom = 2.dp)
                    ) {
                        Text(
                            text = "✦ ${skill.command} ${skill.name}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
            ChatBubble(text = message.content, isUser = true, agendaReferences = message.agendaReferences)
        }
        // Defensive: blank assistant records are filtered in groupChatItems;
        // if one still reaches here it must not paint an empty bubble.
        "assistant" -> if (message.content.isNotBlank()) {
            ChatBubble(text = message.content, isUser = false)
        }
        // Tool messages render as grouped summary rows (see groupChatItems);
        // a stray tool row degrades to a one-call summary instead of detail.
        "tool" -> ToolActivitySummaryRow(
            calls = listOf(message),
            agentRunning = false,
            onClick = {}
        )
    }
}

/**
 * One slash-menu row: an LLM skill (becomes a composer chip and a model
 * run) or a local session command (executed by the app itself on send).
 */
private data class SlashMenuEntry(
    val command: String,
    val name: String,
    val description: String,
    val requiresAgendaReferences: Boolean,
    /** null = local session command, never sent to the model. */
    val skill: SkillDefinition?
)

/** The full slash menu: every skill plus every local session command. */
private fun slashMenuEntries(): List<SlashMenuEntry> =
    ChatSkillRegistry.skills.map {
        SlashMenuEntry(it.command, it.name, it.description, it.requiresAgendaReferences, it)
    } + SessionCommands.ALL.map {
        SlashMenuEntry(it.command, it.name, it.description, requiresAgendaReferences = false, skill = null)
    }

/**
 * Entries whose command extends the typed prefix while the draft is an
 * unfinished slash command. Empty once the leading token is exact (the
 * recognition is then a real command, not a suggestion) or not a slash.
 */
private fun slashSuggestionsFor(input: String): List<SlashMenuEntry> {
    val trimmed = input.trim()
    if (!trimmed.startsWith("/") || trimmed.contains('\n')) return emptyList()
    val token = trimmed.takeWhile { !it.isWhitespace() }
    val entries = slashMenuEntries()
    if (token.length <= 1) return entries // just "/" - list all
    if (entries.any { it.command == token }) return emptyList() // exact match
    return entries.filter { it.command.startsWith(token, ignoreCase = false) }
}

/**
 * Exact leading-command split for the composer: "/org" or "/org extra"
 * (even with multiline arguments behind the token) resolve to the skill and
 * the remaining prose, while "/organization" and unknown "/" tokens return
 * null so they stay as plain visible draft text the ViewModel resolves or
 * rejects. Session commands (/fork) deliberately never match here - they
 * stay text until send.
 */
private fun parseLeadingSkill(text: String): Pair<SkillDefinition, String>? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("/")) return null
    val token = trimmed.takeWhile { !it.isWhitespace() }
    val skill = ChatSkillRegistry.skills.firstOrNull { it.command == token } ?: return null
    return skill to trimmed.removePrefix(token).trim()
}

/**
 * Inline slash-command list anchored above the composer (never a dialog):
 * shown while the draft looks like an unfinished "/" command, or opened
 * from the toolbar plus menu. Picking a skill moves the command into the
 * composer's skill chip and keeps the rest of the draft; picking a local
 * session command puts its exact text into the composer for send.
 */
@Composable
private fun SlashSuggestionPanel(
    entries: List<SlashMenuEntry>,
    showClose: Boolean,
    onPick: (SlashMenuEntry) -> Unit,
    onClose: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (showClose) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 4.dp, end = 4.dp)
                ) {
                    Text(
                        text = "技能 / 斜杠命令",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = OrgMono),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "关闭技能列表",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(32.dp)
                            .clickable(onClick = onClose)
                            .padding(8.dp)
                    )
                }
            }
            entries.forEach { entry ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(entry) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = entry.command,
                            style = MaterialTheme.typography.titleSmall.copy(fontFamily = OrgMono),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = entry.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (entry.requiresAgendaReferences) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "需引用",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        } else if (entry.skill == null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "本地",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    Text(
                        text = entry.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** Removable skill chip inside the composer: mono command + explicit X. */
@Composable
private fun ComposerSkillChip(label: String, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp)
            )
            Icon(
                Icons.Default.Close,
                contentDescription = "移除技能",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .size(30.dp)
                    .clickable(onClick = onRemove)
                    .padding(7.dp)
            )
        }
    }
}

/** Removable Agenda-reference chip: truncated title + explicit X. */
@Composable
private fun ComposerReferenceChip(label: String, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.tertiaryContainer
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp)
            )
            Icon(
                Icons.Default.Close,
                contentDescription = "移除引用",
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier
                    .size(30.dp)
                    .clickable(onClick = onRemove)
                    .padding(7.dp)
            )
        }
    }
}

/** Assistant-side pulsing dots: shown only while running with no text yet. */
@Composable
private fun WaitingDots() {
    val transition = rememberInfiniteTransition(label = "waiting")
    val alphas = List(DOT_COUNT) { index ->
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 450,
                    delayMillis = index * 160,
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dot$index"
        )
    }
    Surface(
        shape = RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = 6.dp,
            bottomEnd = 16.dp
        ),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            alphas.forEach { alpha ->
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .alpha(alpha.value)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
        }
    }
}

private const val DOT_COUNT = 3

@Composable
private fun ChatBubble(
    text: String,
    isUser: Boolean,
    isStreaming: Boolean = false,
    agendaReferences: List<AgendaContextReference> = emptyList()
) {
    // Compact whole-message copy affordance. This supplements — never
    // replaces — real text selection: bodies stay long-press selectable and
    // links stay tappable; this only guarantees a one-tap copy of the full
    // raw message on every device.
    val clipboard = LocalClipboardManager.current
    val copyAction: () -> Unit = {
        val raw = text.removeSuffix(" ▌")
        clipboard.setText(AnnotatedString(raw))
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        // Keep the affordance on the screen-edge side of the bubble so it
        // never squeezes the bubble's 300dp content width from the reading
        // side.
        if (isUser) {
            BubbleCopyButton(onCopy = copyAction)
        }
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 6.dp,
                bottomEnd = if (isUser) 6.dp else 16.dp
            ),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = if (isUser) androidx.compose.foundation.BorderStroke(
                1.dp, MaterialTheme.colorScheme.outlineVariant
            ) else null,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            if (isUser) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (agendaReferences.isNotEmpty()) {
                        Column(
                            modifier = Modifier.padding(bottom = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            agendaReferences.forEach { reference ->
                                Text(
                                    text = "引用 · ${reference.todo?.let { "$it " } ?: ""}${reference.title} · ${reference.fileName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    // BasicText, like the assistant renderer: the app's
                    // material3-alpha Text loses SelectionContainer
                    // selection, and user bubbles must stay copyable too.
                    SelectionContainer {
                        BasicText(
                            text = text + if (isStreaming) " ▌" else "",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                // Assistant text renders Markdown (headings/lists/bold/code/links)
                // and stays selectable; user bubbles remain plain (UI-2).
                AssistantMarkdown(
                    text = text + if (isStreaming) " ▌" else "",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
        if (!isUser) {
            BubbleCopyButton(onCopy = copyAction)
        }
    }
}

/** Discreet per-bubble copy icon: 28dp target, muted tint, no label noise. */
@Composable
private fun BubbleCopyButton(onCopy: () -> Unit) {
    IconButton(
        onClick = onCopy,
        modifier = Modifier
            .padding(start = 2.dp, end = 2.dp, bottom = 2.dp)
            .size(30.dp)
    ) {
        Icon(
            Icons.Default.ContentCopy,
            contentDescription = "复制整条消息",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(15.dp)
        )
    }
}

@Composable
private fun AgendaContextPickerDialog(
    uiState: ChatUiState,
    onToggle: (AgendaContextOption) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val selectedIds = remember(uiState.selectedAgendaReferences) {
        uiState.selectedAgendaReferences.mapTo(mutableSetOf()) { it.id }
    }
    val choices = remember(uiState.agendaContextOptions, query) {
        val normalized = query.trim().lowercase()
        uiState.agendaContextOptions.filter { option ->
            normalized.isEmpty() || listOf(
                option.entry.title,
                option.entry.todo.orEmpty(),
                option.fileName,
                option.hierarchy
            ).any { it.lowercase().contains(normalized) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("引用 Agenda 项目") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "选择一个或多个条目作为本条消息的上下文。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("搜索标题、TODO 或文件") }
                )
                if (uiState.loadingAgendaContextOptions) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).size(24.dp))
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
                ) {
                    if (!uiState.loadingAgendaContextOptions && choices.isEmpty()) {
                        item {
                            Text(
                                "没有找到可引用的 Agenda 条目。",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 16.dp)
                            )
                        }
                    }
                    items(choices, key = { it.id }) { option ->
                        val isLoading = option.id in uiState.loadingAgendaReferenceIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isLoading) { onToggle(option) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = option.id in selectedIds || isLoading,
                                onCheckedChange = { onToggle(option) },
                                enabled = !isLoading
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${option.entry.todo?.let { "$it " } ?: ""}${option.entry.title}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${option.fileName} · ${option.hierarchy}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (isLoading) CircularProgressIndicator(modifier = Modifier.padding(start = 6.dp).size(18.dp))
                        }
                    }
                }
                Text(
                    "已选 ${uiState.selectedAgendaReferences.size} 项",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
/**
 * Lifecycle of one tool call, derived from the live approval state and the
 * stored result summary (the repository marks error results with a leading
 * ✗). DENIED/VOIDED are terminal even without a result, and a call with no
 * result once the run has stopped is interrupted rather than successful —
 * nothing here fabricates a completed state.
 */
private enum class ToolStepStatus { RUNNING, PENDING_APPROVAL, DONE, FAILED, DENIED, VOIDED, INTERRUPTED }

private fun ChatMessageView.stepStatus(agentRunning: Boolean): ToolStepStatus {
    val result = toolResultSummary
    return when {
        approvalState == ApprovalState.PENDING -> ToolStepStatus.PENDING_APPROVAL
        approvalState == ApprovalState.DENIED -> ToolStepStatus.DENIED
        approvalState == ApprovalState.VOIDED -> ToolStepStatus.VOIDED
        result?.startsWith("✗") == true -> ToolStepStatus.FAILED
        result != null -> ToolStepStatus.DONE
        agentRunning -> ToolStepStatus.RUNNING
        else -> ToolStepStatus.INTERRUPTED
    }
}

/** In-flight activity phrase for the timeline, mapped from real tool names. */
private fun toolActivityPhrase(toolName: String?): String = when (toolName) {
    "org_list_files" -> "正在列出文件"
    "org_search" -> "正在搜索笔记"
    "org_read_file" -> "正在读取笔记"
    "org_parse_outline" -> "正在解析大纲"
    "org_create_file" -> "正在创建笔记"
    "org_write_file" -> "正在更新笔记"
    "org_integrate" -> "正在整合笔记"
    "org_archive_done" -> "正在归档完成项"
    "git_sync" -> "正在同步仓库"
    "org_delete_file" -> "正在删除笔记"
    "org_rename_file" -> "正在重命名笔记"
    // Provider-hosted (server-side) search; never a local tool execution.
    HOSTED_SEARCH_ROW_NAME -> "正在联网搜索"
    // Unknown names stay generic instead of claiming a known activity.
    else -> "正在执行工具"
}

/** Short readable step title for the detail sheet; the raw name shows on expand. */
private fun toolStepName(toolName: String?): String = when (toolName) {
    "org_list_files" -> "列出文件"
    "org_search" -> "搜索笔记"
    "org_read_file" -> "读取笔记"
    "org_parse_outline" -> "解析大纲"
    "org_create_file" -> "创建笔记"
    "org_write_file" -> "更新笔记"
    "org_integrate" -> "整合笔记"
    "org_archive_done" -> "归档完成项"
    "git_sync" -> "同步仓库"
    "org_delete_file" -> "删除笔记"
    "org_rename_file" -> "重命名笔记"
    HOSTED_SEARCH_ROW_NAME -> "联网搜索"
    else -> toolName ?: "工具调用"
}

private fun ToolStepStatus.label(): String = when (this) {
    ToolStepStatus.RUNNING -> "执行中"
    ToolStepStatus.PENDING_APPROVAL -> "等待确认"
    ToolStepStatus.DONE -> "已执行"
    ToolStepStatus.FAILED -> "失败"
    ToolStepStatus.DENIED -> "已拒绝"
    ToolStepStatus.VOIDED -> "已作废"
    ToolStepStatus.INTERRUPTED -> "已中断"
}

@Composable
private fun ToolStepStatus.statusColor(): Color = when (this) {
    ToolStepStatus.RUNNING -> MaterialTheme.colorScheme.primary
    ToolStepStatus.PENDING_APPROVAL -> MaterialTheme.colorScheme.tertiary
    ToolStepStatus.FAILED, ToolStepStatus.DENIED, ToolStepStatus.VOIDED -> MaterialTheme.colorScheme.error
    ToolStepStatus.DONE, ToolStepStatus.INTERRUPTED -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Small native status marker; the only motion is the running spinner. */
@Composable
private fun ToolStepStatus.statusIcon() {
    val tint = statusColor()
    val modifier = Modifier.size(16.dp)
    when (this) {
        ToolStepStatus.RUNNING -> CircularProgressIndicator(
            strokeWidth = 2.dp, color = tint, modifier = modifier
        )
        ToolStepStatus.PENDING_APPROVAL ->
            Icon(Icons.Outlined.HourglassTop, null, tint = tint, modifier = modifier)
        ToolStepStatus.DONE ->
            Icon(Icons.Default.CheckCircle, null, tint = tint, modifier = modifier)
        ToolStepStatus.FAILED ->
            Icon(Icons.Default.ErrorOutline, null, tint = tint, modifier = modifier)
        ToolStepStatus.DENIED ->
            Icon(Icons.Default.Block, null, tint = tint, modifier = modifier)
        ToolStepStatus.VOIDED ->
            Icon(Icons.Default.Cancel, null, tint = tint, modifier = modifier)
        ToolStepStatus.INTERRUPTED ->
            Icon(Icons.Default.Stop, null, tint = tint, modifier = modifier)
    }
}

/**
 * Quiet one-line activity summary for one agent turn: status marker, concise
 * Chinese state with the live call count, and a subtle chevron. Counts and
 * state recompute on every transcript emission; the total count stays
 * visible alongside pending/failure counts. Tapping opens the detail sheet.
 */
@Composable
private fun ToolActivitySummaryRow(
    calls: List<ChatMessageView>,
    agentRunning: Boolean,
    onClick: () -> Unit
) {
    val statuses = remember(calls, agentRunning) { calls.map { it.stepStatus(agentRunning) } }
    val pending = statuses.count { it == ToolStepStatus.PENDING_APPROVAL }
    val running = statuses.count { it == ToolStepStatus.RUNNING }
    val failed = statuses.count {
        it == ToolStepStatus.FAILED || it == ToolStepStatus.DENIED || it == ToolStepStatus.VOIDED
    }
    val interrupted = statuses.count { it == ToolStepStatus.INTERRUPTED }
    val settled = calls.size - running - pending
    val (status, label) = when {
        pending > 0 -> ToolStepStatus.PENDING_APPROVAL to buildString {
            append("待确认 · $pending 项")
            if (pending < calls.size) append(" · ${calls.size} 次调用")
        }
        running > 0 -> {
            // Latest in-flight call is the current activity; unknown tools stay generic.
            val activity = statuses.indexOfLast { it == ToolStepStatus.RUNNING }
                .takeIf { it >= 0 }
                ?.let { toolActivityPhrase(calls[it].toolName) }
                ?: "正在执行工具"
            ToolStepStatus.RUNNING to "$activity · $settled/${calls.size}"
        }
        failed > 0 -> ToolStepStatus.FAILED to buildString {
            append("${calls.size} 次调用 · $failed 项失败")
            if (interrupted > 0) append(" · 已中断")
        }
        interrupted > 0 -> ToolStepStatus.INTERRUPTED to "${calls.size} 次调用 · 已中断"
        else -> ToolStepStatus.DONE to "已完成 · ${calls.size} 次调用"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(onClickLabel = "查看工具调用详情", onClick = onClick)
            .padding(horizontal = 4.dp)
    ) {
        status.statusIcon()
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = status.statusColor(),
            fontWeight = if (status == ToolStepStatus.RUNNING || status == ToolStepStatus.PENDING_APPROVAL) {
                FontWeight.Medium
            } else {
                FontWeight.Normal
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * Expandable per-call detail sheet for one tool-call group. Calls arrive
 * live from the transcript, so appended calls and status/result updates
 * appear while it stays open; per-step expansion is keyed by call id and
 * survives streaming recompositions. Bounded to ~80% of the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolCallsDetailSheet(
    calls: List<ChatMessageView>,
    agentRunning: Boolean,
    onDismiss: () -> Unit
) {
    val statuses = remember(calls, agentRunning) { calls.map { it.stepStatus(agentRunning) } }
    val failed = statuses.count {
        it == ToolStepStatus.FAILED || it == ToolStepStatus.DENIED || it == ToolStepStatus.VOIDED
    }
    val pending = statuses.count { it == ToolStepStatus.PENDING_APPROVAL }
    val running = statuses.count { it == ToolStepStatus.RUNNING }
    val interrupted = statuses.count { it == ToolStepStatus.INTERRUPTED }
    val headerSummary = buildString {
        append("共 ${calls.size} 次调用")
        if (running > 0) append(" · $running 项执行中")
        if (pending > 0) append(" · $pending 项待确认")
        if (failed > 0) append(" · $failed 项失败")
        if (interrupted > 0) append(" · 已中断")
    }
    val expandedIds = remember { mutableStateOf(setOf<String>()) }
    val clipboard = LocalClipboardManager.current
    val copyText: (String) -> Unit = { clipboard.setText(AnnotatedString(it)) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 8.dp, top = 2.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "执行过程",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = headerSummary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(calls, key = { it.id }) { call ->
                    val id = call.id
                    ToolCallStepRow(
                        call = call,
                        agentRunning = agentRunning,
                        expanded = id in expandedIds.value,
                        onToggle = {
                            expandedIds.value =
                                if (id in expandedIds.value) expandedIds.value - id
                                else expandedIds.value + id
                        },
                        onCopy = copyText
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

/** One chronological step: collapsed row (status, name, digest, caret) plus expandable payload. */
@Composable
private fun ToolCallStepRow(
    call: ChatMessageView,
    agentRunning: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCopy: (String) -> Unit
) {
    val status = remember(call, agentRunning) { call.stepStatus(agentRunning) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(
                    onClickLabel = if (expanded) "收起详情" else "展开详情",
                    onClick = onToggle
                )
                .padding(horizontal = 24.dp, vertical = 2.dp)
        ) {
            status.statusIcon()
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = toolStepName(call.toolName),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                call.toolArgsDigest?.takeIf { it.isNotBlank() }?.let { digest ->
                    Text(
                        text = digest,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        if (expanded) {
            ToolCallStepDetails(call = call, status = status, onCopy = onCopy)
        }
    }
}

/** Expanded technical payload: raw tool name, arguments and the exact result/error. */
@Composable
private fun ToolCallStepDetails(
    call: ChatMessageView,
    status: ToolStepStatus,
    onCopy: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 48.dp, end = 24.dp, top = 2.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = call.toolName ?: "(unknown)",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        call.toolArgsDigest?.takeIf { it.isNotBlank() }?.let { args ->
            ToolPayloadBlock(label = "参数", payload = args, onCopy = onCopy)
        }
        val result = call.toolResultSummary
        when {
            // Hosted-search rows: provider-reported sources become clickable
            // link rows (title opens the URL); the raw block stays copyable.
            result != null && call.toolName == HOSTED_SEARCH_ROW_NAME -> {
                val sources = hostedSearchSources(result)
                if (sources.isNotEmpty()) {
                    Text(
                        text = "来源（${sources.size}）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                    val context = androidx.compose.ui.platform.LocalContext.current
                    sources.forEach { (title, url) ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = OrgMono),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            // Same external-browser flow as reply links
                            // (chooser + toast fallback), never the in-app
                            // UriHandler.
                            modifier = Modifier.clickable {
                                runCatching { openChatLink(context, uriHandler, url) }
                            }
                        )
                    }
                }
                ToolPayloadBlock(
                    label = if (status == ToolStepStatus.FAILED) "结果（失败）" else "结果",
                    payload = result,
                    onCopy = onCopy
                )
            }
            // Exact stored result/error text (✗ marker included) is preserved.
            result != null -> ToolPayloadBlock(
                label = if (status == ToolStepStatus.FAILED) "结果（失败）" else "结果",
                payload = result,
                onCopy = onCopy
            )
            status == ToolStepStatus.DENIED -> DetailPlaceholder("未执行（已拒绝）")
            status == ToolStepStatus.VOIDED -> DetailPlaceholder("未执行（已作废）")
            status == ToolStepStatus.PENDING_APPROVAL -> DetailPlaceholder("等待确认后执行")
            status == ToolStepStatus.INTERRUPTED -> DetailPlaceholder("执行未完成（已中断）")
            else -> DetailPlaceholder("等待执行结果…")
        }
    }
}

@Composable
private fun DetailPlaceholder(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Payload length over which the block starts collapsed with an explicit expand. */
private const val ToolPayloadCollapsedChars = 600

/** Transcript row name for provider-hosted web search (mirrors AgentLoop constant). */
private const val HOSTED_SEARCH_ROW_NAME = "web_search"

/**
 * Source lines for hosted-search results: "title" on one line, a real
 * provider-reported URL on the next. Rendered as CLICKABLE rows (never
 * fabricated: only URLs the provider actually reported).
 */
private fun hostedSearchSources(result: String): List<Pair<String, String>> {
    val lines = result.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val sources = mutableListOf<Pair<String, String>>()
    var pendingTitle: String? = null
    for (line in lines) {
        if (line.startsWith("http://") || line.startsWith("https://")) {
            sources.add((pendingTitle ?: line) to line)
            pendingTitle = null
        } else {
            pendingTitle = line
        }
    }
    return sources
}

/**
 * Monospace, selectable payload block. Long content starts bounded with a
 * 查看完整内容 toggle and an explicit copy, so full detail is always
 * reachable without dominating the sheet.
 */
@Composable
private fun ToolPayloadBlock(
    label: String,
    payload: String,
    onCopy: (String) -> Unit
) {
    val collapsible = payload.length > ToolPayloadCollapsedChars
    var showFull by remember(payload) { mutableStateOf(!collapsible) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (payload.isNotBlank()) {
                TextButton(
                    onClick = { onCopy(payload) },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("复制", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest
        ) {
            SelectionContainer {
                // BasicText for the same reason as the chat bodies: the
                // material3-alpha Text loses selection on this text stack.
                BasicText(
                    text = if (showFull) {
                        payload
                    } else {
                        payload.take(ToolPayloadCollapsedChars) + "…（共 ${payload.length} 字符）"
                    },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = OrgMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }
        if (collapsible) {
            TextButton(
                onClick = { showFull = !showFull },
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text(
                    text = if (showFull) "收起" else "查看完整内容",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}
