package com.orgutil.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.orgutil.domain.agenda.OrgAgenda
import com.orgutil.domain.agenda.OrgAgendaEntry
import com.orgutil.domain.agenda.OrgGoalStats
import com.orgutil.domain.agenda.OrgAgendaParser
import com.orgutil.domain.agenda.HabitCutoff
import com.orgutil.domain.agenda.HabitCutoffSource
import com.orgutil.domain.agenda.HabitDeadlineProximity
import com.orgutil.domain.agenda.OrgHabit
import com.orgutil.domain.agenda.habitCutoff
import com.orgutil.pomodoro.PomodoroPhase
import com.orgutil.pomodoro.PomodoroSession
import com.orgutil.pomodoro.PomodoroSettings
import com.orgutil.ui.components.OrgTopBar
import com.orgutil.ui.components.OrgTopBarIcon
import com.orgutil.ui.components.OrgMonoChip
import com.orgutil.ui.components.OrgStateChip
import com.orgutil.ui.components.OrgTopBar
import com.orgutil.ui.components.OrgTopBarIcon
import com.orgutil.ui.components.OrgMonoChip
import com.orgutil.ui.components.orgStateIsDone
import com.orgutil.ui.components.OrgTopBar
import com.orgutil.ui.components.OrgTopBarIcon
import com.orgutil.ui.components.OrgMonoChip
import com.orgutil.ui.components.PriorityChip
import com.orgutil.ui.theme.LocalExtendedColors
import com.orgutil.ui.theme.OrgMono
import com.orgutil.ui.viewmodel.AgendaUiState
import com.orgutil.ui.viewmodel.AgendaViewMode
import com.orgutil.ui.viewmodel.AgendaViewModel
import com.orgutil.widget.AgendaWidgetOpenRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AgendaScreen(
    onFileSelected: (Uri, Int?, Int?, String?) -> Unit,
    widgetRequest: AgendaWidgetOpenRequest? = null,
    // Called once when this widget request's pomodoro part has been handled
    // (dialog opened OR missing-entry feedback shown), so the shell can
    // suppress a replay on tab return.
    onPomodoroRequestHandled: () -> Unit = {},
    // Called once when this widget request's 总目标 part has been handled
    // (sheet opened), same consume-once suppression as pomodoro above.
    onGoalRequestHandled: () -> Unit = {},
    viewModel: AgendaViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val pomodoro by viewModel.pomodoro.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Success acknowledgements are one-shot channel events: each shows a
    // transient snackbar exactly once and cannot replay on recomposition or
    // tab re-entry (failures stay modal dialogs via the uiState notice).
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snackbarHostState) {
        viewModel.successNotices.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // Pomodoro start dialog target + special-permission flows. Overlay and
    // exact-alarm access are user-granted special permissions: they are
    // explained and requested right here, at the point they become relevant,
    // and denial only degrades to the notification/inexact fallback.
    var pomodoroTarget by remember { mutableStateOf<OrgAgendaEntry?>(null) }
    // Contextual action sheet target: long-press (or the ⋮ affordance) on an
    // agenda row opens the entry's sheet-only editors (habit, full planning
    // dates) — the row's quick actions (TODO chip, Schedule, pomodoro) live
    // on the row itself, and double-tap opens the source file.
    var actionsTarget by remember { mutableStateOf<OrgAgendaEntry?>(null) }
    var permissionTick by remember { mutableIntStateOf(0) }
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { permissionTick++ }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permissionTick++ }

    fun requestOverlayPermission() {
        overlayPermissionLauncher.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        )
    }

    fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            overlayPermissionLauncher.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
        }
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Re-enters composition on every tab switch back to Agenda: silently
    // reload so edits (also external ones) show up without tapping refresh.
    LaunchedEffect(Unit) {
        viewModel.onAgendaOpened()
        viewModel.refresh(showLoading = false)
    }
    LaunchedEffect(widgetRequest?.requestId) {
        widgetRequest?.modeName
            ?.let { runCatching { AgendaViewMode.valueOf(it) }.getOrNull() }
            ?.let(viewModel::setMode)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            // Lifted clear of the quick-capture FAB (56dp + margin) so the
            // acknowledgement stays fully visible above it and the bottom bar.
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = 88.dp)
            )
        },
        topBar = {
            Column {
                OrgTopBar(
                    title = "GTD Agenda",
                    subtitle = {
                        Text(
                            text = remember { LocalDate.now().format(HEADER_DATE) },
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    actions = {
                        // One-tap archive of every DONE item (ask 6): planning
                        // is read-only; the confirmation dialog shows the count.
                        if (uiState.archiveBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(24.dp)
                                    .padding(end = 12.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            OrgTopBarIcon(
                                icon = Icons.Default.Archive,
                                contentDescription = "Archive done items",
                                onClick = viewModel::prepareArchive
                            )
                        }
                        OrgTopBarIcon(
                            icon = Icons.Default.Refresh,
                            contentDescription = "Refresh agenda",
                            onClick = viewModel::refresh
                        )
                    }
                )
                AgendaModePills(
                    selectedMode = uiState.selectedMode,
                    onModeSelected = viewModel::setMode
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
                uiState.isLoading -> LoadingIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )

                uiState.error != null -> AgendaError(
                    message = uiState.error.orEmpty(),
                    onDismiss = viewModel::clearError,
                    modifier = Modifier.align(Alignment.Center)
                )

                uiState.agenda == null -> Text(
                    text = "No agenda data",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge
                )

                else -> Column(modifier = Modifier.fillMaxSize()) {
                    // Live session banner: phase + remaining (ticked against
                    // the persisted absolute end time) + stop, above the list.
                    pomodoro?.let { session ->
                        PomodoroStatusBanner(session = session, onStop = viewModel::stopPomodoro)
                    }
                    AgendaContent(
                        uiState = uiState,
                        widgetRequest = widgetRequest,
                        onFileSelected = onFileSelected,
                        activePomodoro = pomodoro,
                        onEntryActions = { entry -> actionsTarget = entry },
                        onEntryTodo = viewModel::editTodoKeyword,
                        onEntryWaitReason = viewModel::editWaitReason,
                        onEntrySchedule = viewModel::editSchedule,
                        onStartPomodoro = { entry -> pomodoroTarget = entry },
                        // Widget timer tap whose exact entry vanished: loud
                        // dead-end instead of attaching the timer elsewhere.
                        onWidgetPomodoroMissing = {
                            scope.launch {
                                snackbarHostState.showSnackbar("番茄钟：未找到该条目，可能已被移动、改名或删除")
                            }
                        },
                        onPomodoroRequestHandled = onPomodoroRequestHandled,
                        onGoalRequestHandled = onGoalRequestHandled
                    )
                }
            }
        }
    }
    // Archive confirmation: count + bounded preview before anything is written.
    uiState.archivePlan?.let { plan ->
        AlertDialog(
            onDismissRequest = viewModel::dismissArchivePlan,
            title = { Text("Archive DONE items") },
            text = {
                Column {
                    Text(
                        text = "Move ${plan.count} DONE item(s) to gtd/archive.org?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "CANCELLED and DROPPED items stay in place. Subtrees move verbatim; " +
                            "originals are backed up in app storage and restored on failure.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (plan.preview.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            plan.preview.forEach { line ->
                                Text(
                                    text = "· $line",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (plan.moreCount > 0) {
                                Text(
                                    text = "… and ${plan.moreCount} more",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = viewModel::confirmArchive,
                    enabled = !uiState.archiveBusy && plan.count > 0
                ) { Text("Archive") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissArchivePlan) { Text("Cancel") }
            }
        )
    }

    // Archive failure notice; a successful run is acknowledged by snackbar.
    uiState.archiveNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = viewModel::dismissArchiveNotice,
            title = { Text("Archive") },
            text = { Text(notice) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissArchiveNotice) { Text("OK") }
            }
        )
    }

    // TODO keyword picker for the targeted agenda entry.
    uiState.todoEditTarget?.let { target ->
        TodoKeywordDialog(
            entry = target,
            busy = uiState.todoEditBusy,
            onPick = viewModel::applyTodoKeyword,
            // WAIT is a note-taking keyword (w@): picking it for a heading
            // not yet WAIT swaps the picker for the 等待原因 prompt BEFORE
            // any mutation; cancelling that prompt changes nothing.
            onPickWait = viewModel::requestWaitReason,
            // Re-tapping WAIT on an item already WAIT opens the current
            // note's editor (view / edit / add) — not a no-op write.
            onEditWait = { viewModel.editWaitReason(target) },
            onClear = { viewModel.applyTodoKeyword(null) },
            onDismiss = viewModel::dismissTodoEdit
        )
    }

    // WAIT 等待原因 editor: fresh-transition prompt or existing-note editor.
    uiState.waitReasonTarget?.let { target ->
        WaitReasonDialog(
            entry = target,
            busy = uiState.waitReasonBusy,
            onSave = viewModel::saveWaitReason,
            onDismiss = viewModel::dismissWaitReason
        )
    }

    // WAIT reason write failure notice; success is acknowledged by snackbar.
    uiState.waitNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = viewModel::dismissWaitNotice,
            title = { Text("等待原因") },
            text = { Text(notice) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissWaitNotice) { Text("OK") }
            }
        )
    }

    // TODO keyword edit failure notice; success is acknowledged by snackbar.
    uiState.todoNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = viewModel::dismissTodoNotice,
            title = { Text("TODO 状态") },
            text = { Text(notice) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissTodoNotice) { Text("OK") }
            }
        )
    }

    // Explicit habit schedule setup for the targeted agenda entry.
    uiState.habitEditTarget?.let { target ->
        HabitScheduleDialog(
            entry = target,
            busy = uiState.habitBusy,
            onConfirm = viewModel::applyHabitSchedule,
            onDismiss = viewModel::dismissHabitEdit
        )
    }

    // Habit style change failure notice; success is acknowledged by snackbar.
    uiState.habitNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = viewModel::dismissHabitNotice,
            title = { Text("习惯属性") },
            text = { Text(notice) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissHabitNotice) { Text("OK") }
            }
        )
    }

    // Planning dates (SCHEDULED / DEADLINE) editor for the targeted entry.
    uiState.planningEditTarget?.let { target ->
        PlanningDatesDialog(
            entry = target,
            busy = uiState.planningBusy,
            onConfirm = viewModel::applyPlanningDates,
            onDismiss = viewModel::dismissPlanningEdit
        )
    }

    // Planning date change failure notice; success is acknowledged by snackbar.
    uiState.planningNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPlanningNotice,
            title = { Text("计划日期") },
            text = { Text(notice) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissPlanningNotice) { Text("OK") }
            }
        )
    }

    // Row Schedule quick action: the SCHEDULED-only editor (DEADLINE keeps
    // its bytes); failures reuse the planning notice dialog above.
    uiState.scheduleEditTarget?.let { target ->
        ScheduleQuickDialog(
            entry = target,
            busy = uiState.planningBusy,
            onConfirm = viewModel::applyScheduleOnly,
            onDismiss = viewModel::dismissScheduleEdit
        )
    }

    // Pomodoro start dialog for the targeted entry.
    pomodoroTarget?.let { target ->
        PomodoroStartDialog(
            entry = target,
            settings = viewModel.pomodoroSettings(),
            busy = uiState.pomodoroBusy,
            hasOverlay = Settings.canDrawOverlays(context),
            hasExactAlarm = viewModel.canScheduleExactAlarms(),
            hasNotifications = viewModel.notificationsEnabled(),
            onGrantOverlay = ::requestOverlayPermission,
            onGrantExactAlarm = ::requestExactAlarmPermission,
            onGrantNotifications = ::requestNotificationPermission,
            onStart = { settings ->
                viewModel.savePomodoroSettings(settings)
                viewModel.startPomodoro(target)
                pomodoroTarget = null
            },
            onDismiss = { pomodoroTarget = null },
            // Recompute special-permission state when returning from Settings.
            refreshKey = permissionTick
        )
    }

    // Pomodoro start failure notice; success is acknowledged by snackbar.
    uiState.pomodoroNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPomodoroNotice,
            title = { Text("番茄钟") },
            text = { Text(notice) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissPomodoroNotice) { Text("OK") }
            }
        )
    }

    // Long-press / ⋮ contextual sheet: the row's secondary actions that have
    // no direct affordance — habit setup and the full planning-date editor.
    // (TODO state, pomodoro and SCHEDULED live on the row itself now.)
    actionsTarget?.let { target ->
        EntryActionsSheet(
            entry = target,
            onEditHabit = {
                viewModel.editHabit(target)
                actionsTarget = null
            },
            onEditPlanning = {
                viewModel.editPlanningDates(target)
                actionsTarget = null
            },
            onDismiss = { actionsTarget = null }
        )
    }
}

/** Draft pills: active = highest container + teal dot; inactive = low container. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgendaModePills(
    selectedMode: AgendaViewMode,
    onModeSelected: (AgendaViewMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AgendaViewMode.entries.forEach { mode ->
            val active = selectedMode == mode
            Surface(
                onClick = { onModeSelected(mode) },
                shape = RoundedCornerShape(8.dp),
                color = if (active) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (active) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (active) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AgendaContent(
    uiState: AgendaUiState,
    widgetRequest: AgendaWidgetOpenRequest? = null,
    onFileSelected: (Uri, Int?, Int?, String?) -> Unit,
    activePomodoro: PomodoroSession? = null,
    onEntryActions: (OrgAgendaEntry) -> Unit = {},
    onEntryTodo: (OrgAgendaEntry) -> Unit = {},
    onEntryWaitReason: (OrgAgendaEntry) -> Unit = {},
    onEntrySchedule: (OrgAgendaEntry) -> Unit = {},
    onStartPomodoro: (OrgAgendaEntry) -> Unit = {},
    onWidgetPomodoroMissing: () -> Unit = {},
    onPomodoroRequestHandled: () -> Unit = {},
    onGoalRequestHandled: () -> Unit = {}
) {
    val agenda = requireNotNull(uiState.agenda)
    // Mode that produced THIS composition's sections - the pomodoro handler
    // below settles against it, so a frame composed before the widget's
    // mode switch lands is skipped rather than misread as "entry missing".
    val composedMode = uiState.selectedMode
    val sections = agenda.sectionsFor(uiState.selectedMode)
    val listState = rememberLazyListState()
    val targetSection = sections.firstOrNull { it.title == widgetRequest?.sectionTitle }
    val targetEntry = targetSection?.let { section ->
        val visibleEntries = if (section.hierarchical) {
            section.entries.flatMap { it.flattenVisibleAgendaEntries() }
        } else section.entries
        val request = widgetRequest
        request?.let { target ->
            visibleEntries.firstOrNull {
                it.fileName == target.fileName &&
                    target.sourceOffset != null &&
                    it.sourceOffset == target.sourceOffset
            }
                ?: visibleEntries.filter { it.fileName == target.fileName && it.title == target.entryTitle }
                    .singleOrNull()
        }
    }
    val targetEntryKey = targetEntry?.widgetAgendaEntryKey()
    // Widget TODO-badge taps open the row's keyword chooser once per request;
    // the guard keeps agenda reloads from resurrecting a dismissed dialog.
    var todoChooserRequestHandled by remember(widgetRequest?.requestId) { mutableStateOf(false) }
    // Widget timer-chip taps: same once-per-request guard, and the entry is
    // verified by EXACT identity (uri + sourceOffset + title) — a moved or
    // renamed entry is never swapped for some same-file/same-title row.
    var pomodoroRequestHandled by remember(widgetRequest?.requestId) { mutableStateOf(false) }
    // Widget 总目标-button taps: once-per-request guard — a consumed request
    // never re-opens the sheet on tab return; each fresh tap gets a fresh
    // request id (parsed at delivery) and passes straight through.
    var goalRequestHandled by remember(widgetRequest?.requestId) { mutableStateOf(false) }
    var showGoalDetail by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(widgetRequest?.requestId) {
        if (widgetRequest?.openGoal == true && !goalRequestHandled) {
            goalRequestHandled = true
            onGoalRequestHandled()
            showGoalDetail = true
        }
    }

    LaunchedEffect(widgetRequest?.requestId, uiState.selectedMode, uiState.agenda) {
        // Widget timer chip: once the requested view mode is the one on
        // screen, consume the request exactly once - even when the section
        // or entry cannot be found at all, a moved/deleted row must fail
        // loudly (onWidgetPomodoroMissing) instead of hanging pending.
        // Transient wrong-mode frames are skipped, never read as missing.
        if (widgetRequest?.openPomodoro == true && !pomodoroRequestHandled) {
            val request = widgetRequest
            val requestedMode = request.modeName
                ?.let { runCatching { AgendaViewMode.valueOf(it) }.getOrNull() }
            if (requestedMode == null || requestedMode == composedMode) {
                pomodoroRequestHandled = true
                onPomodoroRequestHandled()
                val exactEntry = targetEntry?.takeIf { candidate ->
                    request.fileUri != null &&
                        candidate.uri.toString() == request.fileUri &&
                        request.sourceOffset != null &&
                        candidate.sourceOffset == request.sourceOffset &&
                        candidate.title == request.entryTitle
                }
                if (exactEntry != null) onStartPomodoro(exactEntry)
                else onWidgetPomodoroMissing()
            }
        }
        val matchingSection = targetSection ?: return@LaunchedEffect
        if (targetEntry == null) return@LaunchedEffect
        val sectionIndex = sections.indexOf(matchingSection)
        if (sectionIndex >= 0) {
            listState.scrollToItem(sectionIndex + if (uiState.selectedMode == AgendaViewMode.DAILY) 1 else 0)
        }
        if (widgetRequest?.openTodoChooser == true && !todoChooserRequestHandled) {
            todoChooserRequestHandled = true
            onEntryTodo(targetEntry)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        // Bottom clearance: last section must scroll clear of the capture
        // FAB and the bottom bar.
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (uiState.selectedMode == AgendaViewMode.DAILY) {
            item {
                AgendaStatsCard(
                    sections = sections,
                    stats = agenda.goalStats,
                    goalText = agenda.goalText,
                    onShowGoal = { showGoalDetail = true }
                )
            }
        }

        sections.forEach { section ->
            item(key = "${section.title}-card") {
                AgendaSectionCard(
                    section = section,
                    // Stable per-section expansion key: survives recomposition
                    // and process death, independent of list position.
                    sectionKey = "${uiState.selectedMode.name}:${section.title}",
                    scrollTargetKey = targetEntryKey.takeIf { section.title == targetSection?.title },
                    // org-habit-show-habits-only-for-today=t: the consistency
                    // graph belongs to the current-day (Today) habit rows only.
                    showHabitGraph = uiState.selectedMode == AgendaViewMode.DAILY &&
                        section.title == "Today",
                    onEntryClick = { entry ->
                        onFileSelected(
                            entry.uri,
                            entry.titleOffset,
                            entry.title.length,
                            null
                        )
                    },
                    activePomodoro = activePomodoro,
                    onEntryActions = onEntryActions,
                    onEntryTodo = onEntryTodo,
                    onEntryWaitReason = onEntryWaitReason,
                    onEntrySchedule = onEntrySchedule,
                    onStartPomodoro = onStartPomodoro
                )
            }
        }
    }

    // 总目标 detail: same vault goal file (agenda_goal.org) the agenda's
    // goalText came from, presented read-only — opened by the stats card's
    // goal region or directly by the widget's 总目标 button above.
    if (showGoalDetail) {
        GoalDetailSheet(
            goalText = agenda.goalText,
            sourceFileName = "agenda_goal.org",
            onDismiss = { showGoalDetail = false }
        )
    }
}

/**
 * Draft stats card: today-goal progress ring + goal + mono counts.
 * Ring, percent and the 已完成 n/m line all read the SAME shared
 * [OrgGoalStats] snapshot (today's T/N/V/W population — Today, Next actions,
 * Vibing and Waiting groups — plus tasks and habits completed TODAY, one
 * integrated total, no separate habit segment).
 * Chips keep the section counters T/N/W/I and D = completed TODAY (same
 * basis as the stats line, never the old vault-wide raw DONE count).
 * The whole goal region is the 总目标 affordance: tap opens the goal
 * file's detail sheet, with a trailing chevron so the tap target reads
 * as expandable.
 */
@Composable
private fun AgendaStatsCard(
    sections: List<AgendaSection>,
    stats: OrgGoalStats,
    goalText: String,
    onShowGoal: () -> Unit = {}
) {
    // Title-based lookups: section order follows the Doom dashboard and may
    // grow (Vibing/Sandbagging/Done/...), so positional indexes are brittle.
    fun countFor(title: String): Int =
        sections.firstOrNull { it.title == title }?.entries?.size ?: 0
    val counts = listOf(
        countFor("Today"),
        countFor("Next actions"),
        countFor("Waiting / follow-up"),
        countFor("Inbox to clarify")
    )
    // 已完成 n/m — today's integrated goal population (ordinary + habits)
    // from the shared snapshot; the card never re-derives done-ness from any
    // section and never splits a separate habit segment out of it.
    val completedLine = "已完成 ${stats.taskDone}/${stats.taskTotal}"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { stats.progressFraction },
                    modifier = Modifier.size(40.dp),
                    strokeWidth = 3.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
                Text(
                    text = "${stats.progressPercent}%",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                    fontWeight = FontWeight.SemiBold
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        enabled = goalText.isNotBlank(),
                        onClickLabel = "查看总目标",
                        onClick = onShowGoal
                    )
                    .padding(vertical = 2.dp)
            ) {
                if (goalText.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = goalText,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    Text(
                        text = "Today",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = completedLine,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Text(
                    // D is on the SAME done-today basis as the stats line
                    // above it — not the vault-wide DONE-keyword count.
                    text = "T:${counts.getOrElse(0) { 0 }} · N:${counts.getOrElse(1) { 0 }} · " +
                        "W:${counts.getOrElse(2) { 0 }} · I:${counts.getOrElse(3) { 0 }} · " +
                        "D:${stats.taskDone}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}


/** One section = one card holding the rows (draft grouping); header collapses. */
@Composable
private fun AgendaSectionCard(
    section: AgendaSection,
    sectionKey: String,
    scrollTargetKey: String? = null,
    showHabitGraph: Boolean,
    onEntryClick: (OrgAgendaEntry) -> Unit,
    activePomodoro: PomodoroSession? = null,
    onEntryActions: (OrgAgendaEntry) -> Unit = {},
    onEntryTodo: (OrgAgendaEntry) -> Unit = {},
    onEntryWaitReason: (OrgAgendaEntry) -> Unit = {},
    onEntrySchedule: (OrgAgendaEntry) -> Unit = {},
    onStartPomodoro: (OrgAgendaEntry) -> Unit = {}
) {
    // rememberSaveable keyed per section: expansion is stable per section
    // across mode switches, reloads and process death (default expanded).
    var expanded by rememberSaveable(sectionKey) { mutableStateOf(true) }
    LaunchedEffect(scrollTargetKey) {
        if (scrollTargetKey != null) expanded = true
    }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        label = "sectionArrow"
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            // Draft section header: tinted band, 12sp bold title, tap toggles
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f)
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse section" else "Expand section",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(arrowRotation)
                )
                Text(
                    text = section.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${section.entries.size}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                if (section.entries.isEmpty()) {
                    Text(
                        text = section.emptyText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                } else if (section.hierarchical) {
                    // GTD Projects / GTD Areas: one branch per root; descendants
                    // render nested inside their parent, never as peer rows.
                    section.entries.forEachIndexed { index, root ->
                        AgendaTreeBranch(
                            entry = root,
                            depth = 0,
                            showDivider = index > 0,
                            showHabitGraph = showHabitGraph,
                            onEntryClick = onEntryClick,
                            activePomodoro = activePomodoro,
                            onEntryActions = onEntryActions,
                            onEntryTodo = onEntryTodo,
                            onEntryWaitReason = onEntryWaitReason,
                            onEntrySchedule = onEntrySchedule,
                            onStartPomodoro = onStartPomodoro,
                            scrollTargetKey = scrollTargetKey
                        )
                    }
                } else {
                    section.entries.forEachIndexed { index, entry ->
                        AgendaEntryRow(
                            entry = entry,
                            showDivider = index > 0,
                            showHabitGraph = showHabitGraph,
                            onEntryClick = { onEntryClick(entry) },
                            onEntryActions = { onEntryActions(entry) },
                            onEntryTodo = { onEntryTodo(entry) },
                            onEditWaitReason = { onEntryWaitReason(entry) },
                            onEntrySchedule = { onEntrySchedule(entry) },
                            onStartPomodoro = { onStartPomodoro(entry) },
                            pomodoroActive = activePomodoro.isRunningOn(entry),
                            scrollTargetKey = scrollTargetKey
                        )
                    }
                }
            }
        }
    }
}

/**
 * One GTD Projects branch: the heading's own row followed by every descendant
 * heading nested one level deeper. Each row closes over its own entry, so the
 * double-tap-to-open, TODO, Schedule, pomodoro and Habit affordances always
 * target that exact heading — any TODO state (or none at all) is actionable,
 * and a child action never triggers its parent's.
 */
@Composable
private fun AgendaTreeBranch(
    entry: OrgAgendaEntry,
    depth: Int,
    showDivider: Boolean,
    showHabitGraph: Boolean,
    onEntryClick: (OrgAgendaEntry) -> Unit,
    activePomodoro: PomodoroSession? = null,
    onEntryActions: (OrgAgendaEntry) -> Unit = {},
    onEntryTodo: (OrgAgendaEntry) -> Unit = {},
    onEntryWaitReason: (OrgAgendaEntry) -> Unit = {},
    onEntrySchedule: (OrgAgendaEntry) -> Unit = {},
    onStartPomodoro: (OrgAgendaEntry) -> Unit = {},
    scrollTargetKey: String? = null
) {
    AgendaEntryRow(
        entry = entry,
        showDivider = showDivider,
        showHabitGraph = showHabitGraph,
        treeDepth = depth,
        showBreadcrumb = depth == 0,
        onEntryClick = { onEntryClick(entry) },
        onEntryActions = { onEntryActions(entry) },
        onEntryTodo = { onEntryTodo(entry) },
        onEditWaitReason = { onEntryWaitReason(entry) },
        onEntrySchedule = { onEntrySchedule(entry) },
        onStartPomodoro = { onStartPomodoro(entry) },
        pomodoroActive = activePomodoro.isRunningOn(entry),
        scrollTargetKey = scrollTargetKey
    )
    entry.children.forEach { child ->
        AgendaTreeBranch(
            entry = child,
            depth = depth + 1,
            showDivider = false,
            showHabitGraph = showHabitGraph,
            onEntryClick = onEntryClick,
            activePomodoro = activePomodoro,
            onEntryActions = onEntryActions,
            onEntryTodo = onEntryTodo,
            onEntryWaitReason = onEntryWaitReason,
            onEntrySchedule = onEntrySchedule,
            onStartPomodoro = onStartPomodoro,
            scrollTargetKey = scrollTargetKey
        )
    }
}

/**
 * Draft row: mono breadcrumb above, state chip + title left, mono time right.
 * Double-tap opens the source file (a single tap deliberately does nothing,
 * so everyday row taps can't bounce into the editor); long-press or the
 * trailing ⋮ opens the contextual sheet. Direct quick actions on the row:
 * the TODO state chip itself opens the keyword picker (keyword-less rows get
 * a ghost ＋ chip), and tracked headings get a Schedule (SCHEDULED-only)
 * and a pomodoro start button. The one row the running Pomodoro sits on is
 * highlighted — exact heading identity, not just the file — with a soft teal
 * wash, a leading accent bar and a tomato mark.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgendaEntryRow(
    entry: OrgAgendaEntry,
    showDivider: Boolean,
    showHabitGraph: Boolean = false,
    treeDepth: Int = 0,
    showBreadcrumb: Boolean = true,
    onEntryClick: () -> Unit,
    onEntryActions: () -> Unit = {},
    onEntryTodo: () -> Unit = {},
    onEditWaitReason: () -> Unit = {},
    onEntrySchedule: () -> Unit = {},
    onStartPomodoro: () -> Unit = {},
    pomodoroActive: Boolean = false,
    scrollTargetKey: String? = null
) {
    // Quick writes (Schedule / pomodoro CLOCK) go through the surgical write
    // path, which needs the heading's tracked source identity.
    val sourceTracked = entry.sourceOffset >= 0 && entry.titleOffset >= 0
    val todo = entry.todo
    val extended = LocalExtendedColors.current
    val entryKey = entry.widgetAgendaEntryKey()
    val bringIntoViewRequester = remember(entryKey) { BringIntoViewRequester() }
    LaunchedEffect(scrollTargetKey == entryKey) {
        if (scrollTargetKey == entryKey) bringIntoViewRequester.bringIntoView()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .background(
                // Theme token: Classic = teal @10% (the exact historic
                // value), Kraft = solid paper #CFC7AE.
                if (pomodoroActive) {
                    extended.pomodoroRowActive
                } else {
                    Color.Transparent
                },
                RoundedCornerShape(10.dp)
            )
            .combinedClickable(
                // Editor entry is double-tap only; the row's own single tap
                // stays free for scrolling/accidental touches.
                onDoubleClick = onEntryClick,
                onLongClick = onEntryActions,
                onClick = {}
            )
            .semantics {
                if (pomodoroActive) contentDescription = "Pomodoro running on this task"
            }
    ) {
        if (showDivider) {
            androidx.compose.material3.HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            // Running-Pomodoro accent bar on the leading edge of the row.
            if (pomodoroActive) {
                Box(
                    modifier = Modifier
                        .padding(vertical = 9.dp)
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(
                            MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            // Tree indentation: one guide-rail slot per ancestor level so
            // descendant rows visually nest inside their parent branch.
            repeat(treeDepth) {
                Box(
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                )
                Spacer(modifier = Modifier.width(13.dp))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                if (showBreadcrumb && entry.parentTitles.isNotEmpty()) {
                    Text(
                        text = entry.parentTitles.joinToString(" > "),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    entry.priority?.let { PriorityChip(priority = it) }
                    // The visible TODO keyword chip is the picker trigger:
                    // tapping it opens the keyword editor (DONE / NEXT first,
                    // plus an explicit clear). Keyword-less rows get a ghost
                    // ＋ chip so a keyword can also be added.
                    if (todo != null) {
                        OrgStateChip(
                            state = todo,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(
                                    onClickLabel = "Change TODO keyword",
                                    onClick = onEntryTodo
                                )
                        )
                    } else {
                        Surface(
                            onClick = onEntryTodo,
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Transparent,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp, MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier.semantics {
                                contentDescription = "Set TODO keyword"
                            }
                        ) {
                            Text(
                                text = "＋",
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = OrgMono),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                            )
                        }
                    }
                    if (pomodoroActive) {
                        Text(text = "🍅", fontSize = 13.sp)
                    }
                    Text(
                        text = entry.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (entry.todo != null && orgStateIsDone(entry.todo)) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Planning info stays visible but inert (no placeholder
                    // when there is none); full editing lives in the sheet.
                    EntryTimeChip(entry = entry)
                    if (sourceTracked) {
                        // SCHEDULED-only quick action; DEADLINE untouched.
                        RowIconButton(
                            icon = Icons.Default.Schedule,
                            contentDescription = "Schedule (SCHEDULED only)",
                            onClick = onEntrySchedule
                        )
                        // Direct pomodoro start: opens the same start /
                        // configuration flow the sheet used to expose.
                        RowIconButton(
                            icon = Icons.Default.Timer,
                            contentDescription = "Start pomodoro",
                            onClick = onStartPomodoro
                        )
                    }
                    RowIconButton(
                        icon = Icons.Default.MoreVert,
                        contentDescription = "More actions",
                        onClick = onEntryActions
                    )
                }
                // WAIT 等待原因 summary: the current note's first 1–2 lines,
                // subdued under the title — only while the heading IS WAIT
                // and carries a note (no placeholder noise otherwise). Its
                // own tap target opens the full-note editor; it never
                // captures the TODO chip, pomodoro or planning row actions.
                if (todo == "WAIT" && !entry.waitReason.isNullOrBlank()) {
                    Text(
                        text = entry.waitReason.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = 1.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(
                                onClickLabel = "查看或编辑等待原因",
                                onClick = onEditWaitReason
                            )
                            .padding(horizontal = 2.dp, vertical = 1.dp)
                    )
                }
                if (showHabitGraph && entry.habit != null) {
                    // Countdown cutoff priority: the entry's REAL DEADLINE
                    // stamp (OrgAgendaEntry.deadline) first; without one, a
                    // genuine repeater "/Nd" window's effectiveDeadline
                    // (resolved inside, same date the graph's current cycle
                    // ALERT day uses). No DEADLINE and no window → no chip.
                    HabitConsistencyGraph(
                        habit = entry.habit,
                        deadline = entry.deadline
                    )
                }
            }
        }
    }
}

/**
 * One compact 28dp round row affordance (Schedule / pomodoro / ⋮): keeps the
 * row's quick actions directly tappable and independently accessible without
 * stealing the row's own double-tap/long-press gestures.
 */
@Composable
private fun RowIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable(
                onClickLabel = contentDescription,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * Exact running-Pomodoro identity: same file AND same heading position and
 * text. Comparing only the file URI (the old behavior) flags every row that
 * lives in the same org file; the source/title offsets pin the one heading
 * the CLOCK was opened on, and level/title confirm the file still holds the
 * same heading there.
 */
private fun PomodoroSession?.isRunningOn(entry: OrgAgendaEntry): Boolean {
    val session = this ?: return false
    return session.taskUri == entry.uri.toString() &&
        session.taskSourceOffset == entry.sourceOffset &&
        session.taskTitleOffset == entry.titleOffset &&
        session.taskLevel == entry.level &&
        session.taskTitleText == entry.title
}

/**
 * Compact contextual action sheet (long-press or ⋮ on a row): exactly the two
 * secondary editors that have no direct row affordance — habit setup and the
 * full planning-date editor — each with its current state as supporting text.
 * Both need a tracked source position (the surgical write path) and are
 * hidden without it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryActionsSheet(
    entry: OrgAgendaEntry,
    onEditHabit: () -> Unit,
    onEditPlanning: () -> Unit,
    onDismiss: () -> Unit
) {
    val sourceTracked = entry.sourceOffset >= 0 && entry.titleOffset >= 0
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = entry.title.ifBlank { "(untitled)" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = entry.fileName,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )
            if (sourceTracked) {
                SheetAction(
                    icon = Icons.Default.Repeat,
                    label = "习惯设置",
                    supporting = if (entry.habit != null) "已启用（STYLE=habit）" else "未启用",
                    onClick = onEditHabit
                )
                SheetAction(
                    icon = Icons.Default.Event,
                    label = "计划日期",
                    supporting = "SCHEDULED / DEADLINE",
                    onClick = onEditPlanning
                )
            }
        }
    }
}

/** One sheet row: tinted icon, label, supporting state line. */
@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    supporting: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = supporting,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
    }
}

/**
 * Compact live-session banner above the agenda list: phase glyph, task
 * title, mono countdown against the persisted absolute end time, and Stop.
 * The one-second tick is foreground UI only — the timer itself is woken by
 * the single scheduled alarm, never by this loop.
 */
@Composable
private fun PomodoroStatusBanner(
    session: PomodoroSession,
    onStop: () -> Unit
) {
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session.endAtMillis) {
        while (nowMillis < session.endAtMillis) {
            delay(1000)
            nowMillis = System.currentTimeMillis()
        }
    }
    val remaining = ((session.endAtMillis - nowMillis + 999) / 1000).coerceAtLeast(0)
    val remainingText = "${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = when (session.phase) {
                    PomodoroPhase.FOCUS -> "🍅"
                    PomodoroPhase.BREAK -> if (session.breakIsLong) "🏝️" else "☕"
                },
                fontSize = 18.sp
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (session.phase) {
                        PomodoroPhase.FOCUS -> "Focus · ${session.taskTitle}"
                        PomodoroPhase.BREAK ->
                            "${if (session.breakIsLong) "Long" else "Short"} break"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when (session.phase) {
                        PomodoroPhase.FOCUS ->
                            "#${session.completedFocusCount + 1} · CLOCK running"
                        PomodoroPhase.BREAK -> "focus #${session.completedFocusCount} done"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = remainingText,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = OrgMono),
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = onStop) { Text("Stop") }
        }
    }
}

/**
 * Start dialog: what will happen (Org CLOCK in on the heading, one alarm at
 * the end, break overlay/notification), editable durations (Doom defaults
 * 40 / 5 / 20 every 4th), and the three special permissions — each shown
 * with its consequence and a grant button only when missing, denial always
 * leaving a working degraded mode.
 */
@Composable
private fun PomodoroStartDialog(
    entry: OrgAgendaEntry,
    settings: PomodoroSettings,
    busy: Boolean,
    hasOverlay: Boolean,
    hasExactAlarm: Boolean,
    hasNotifications: Boolean,
    onGrantOverlay: () -> Unit,
    onGrantExactAlarm: () -> Unit,
    onGrantNotifications: () -> Unit,
    onStart: (PomodoroSettings) -> Unit,
    onDismiss: () -> Unit,
    refreshKey: Int = 0
) {
    var focus by remember { mutableStateOf(settings.focusMinutes.toString()) }
    var shortBreak by remember { mutableStateOf(settings.shortBreakMinutes.toString()) }
    var longBreak by remember { mutableStateOf(settings.longBreakMinutes.toString()) }
    var longEvery by remember { mutableStateOf(settings.longBreakEvery.toString()) }

    fun toInt(value: String, fallback: Int) = value.trim().toIntOrNull()?.coerceIn(1, 240) ?: fallback

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start pomodoro") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())
            ) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Clocks the heading in (Org CLOCK in LOGBOOK); one alarm " +
                    "ends the focus, closes the clock and shows the break overlay. " +
                    "Starting another task replaces a running session.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DurationField("Focus", focus, { focus = it }, Modifier.weight(1f))
                DurationField("Short", shortBreak, { shortBreak = it }, Modifier.weight(1f))
                DurationField("Long", longBreak, { longBreak = it }, Modifier.weight(1f))
                DurationField("Every", longEvery, { longEvery = it }, Modifier.weight(1f))
            }

            Text(
                text = "minutes · long break every Nth focus",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )

            Column(modifier = Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PermissionRow(
                    granted = hasNotifications,
                    label = "Notifications",
                    grantedText = "status + break alerts",
                    deniedText = "without them only the in-app banner shows",
                    onGrant = onGrantNotifications
                )
                PermissionRow(
                    granted = hasOverlay,
                    label = "Display over other apps",
                    grantedText = "floating break overlay",
                    deniedText = "falls back to a heads-up break notification",
                    onGrant = onGrantOverlay
                )
                PermissionRow(
                    granted = hasExactAlarm,
                    label = "Exact alarms",
                    grantedText = "phase end fires exactly on time",
                    deniedText = "inexact alarm may drift a few minutes in Doze",
                    onGrant = onGrantExactAlarm
                )
            }
            }
        },
        confirmButton = {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                TextButton(
                    onClick = {
                        onStart(
                            PomodoroSettings(
                                focusMinutes = toInt(focus, settings.focusMinutes),
                                shortBreakMinutes = toInt(shortBreak, settings.shortBreakMinutes),
                                longBreakMinutes = toInt(longBreak, settings.longBreakMinutes),
                                longBreakEvery = toInt(longEvery, settings.longBreakEvery)
                            )
                        )
                    }
                ) { Text("Start") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** One numeric duration field with a mono look, kept 1..240 on confirm. */
@Composable
private fun DurationField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = OrgMono),
        modifier = modifier
    )
}

/** One special-permission line: state, consequence, and grant button. */
@Composable
private fun PermissionRow(
    granted: Boolean,
    label: String,
    grantedText: String,
    deniedText: String,
    onGrant: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = if (granted) "✓" else "!",
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = OrgMono),
            fontWeight = FontWeight.Bold,
            color = if (granted) {
                LocalExtendedColors.current.success
            } else {
                LocalExtendedColors.current.warning
            }
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = if (granted) grantedText else deniedText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!granted) {
            OutlinedButton(onClick = onGrant) { Text("Grant") }
        }
    }
}

/**
 * Compact Android equivalent of the Doom org-habit consistency graph:
 * 21 preceding days + today + 7 following days as a responsive strip of
 * small cells under the habit row (org-habit-graph-column=60 is a desktop
 * insertion column and has no phone equivalent). Org's clear/ready/alert/
 * overdue faces map to the theme's info/success/warning/error roles;
 * past unmarked days render dimmed like Org's future faces, done days
 * carry a dot (Org's "*" glyph) and today a distinct outline (Org's "!").
 * On top of Org's coloring, SKIPPED repetition days — strictly past days
 * whose cadence called for a completion that was never recorded (daily
 * habits: every undone day of the recorded active interval, window ignored;
 * period habits: only the rewind-reconstructed due dates with no completion
 * in their window/slot) — carry the dedicated brick-red fill plus a
 * two-tone × cross so they can never blend into the dim future/early cells;
 * rest and pre-start days stay marker-free, and future days are never
 * marked (see [OrgHabit.Day.missed]).
 * [deadline] is the heading's real DEADLINE date: when present, a chip on
 * its own full-width line under the graph counts down calendar days to it
 * (截止 YYYY-MM-DD · 距截止 N 天 / 今天截止 / 已逾期 N 天); with NO real
 * DEADLINE but a genuine repeater "/Nd" window, the chip counts down to
 * the window end (窗口截止 …) — the same [OrgHabit.effectiveDeadline] the
 * graph's current cycle ALERT day uses, so the two can never disagree.
 * Neither exists → no chip and no reserved space (no fabricated deadline).
 */
@Composable
private fun HabitConsistencyGraph(
    habit: OrgHabit,
    deadline: LocalDate? = null
) {
    val extended = LocalExtendedColors.current
    val today = remember { LocalDate.now() }
    val days = remember(habit, today) { habit.buildGraph(today) }
    val cutoff = remember(habit, deadline, today) { habitCutoff(deadline, habit, today) }
    val todayDay = days.firstOrNull { it.isToday }
    val doneCount = days.count { it.done }
    val missedCount = days.count { it.missed }
    val summary = remember(days, doneCount, missedCount) {
        "Habit consistency graph, ${OrgHabit.PRECEDING_DAYS} days before today through " +
            "${OrgHabit.FOLLOWING_DAYS} days after. Done on $doneCount day(s). " +
            "Missed $missedCount day(s). " +
            "Today: ${todayDay?.let { habitDayLabel(it) } ?: "unknown"}."
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .semantics { contentDescription = summary },
            horizontalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            days.forEach { day ->
                val faceColor = when (day.face) {
                    OrgHabit.Face.CLEAR -> extended.info
                    OrgHabit.Face.READY -> extended.success
                    OrgHabit.Face.ALERT -> extended.warning
                    OrgHabit.Face.OVERDUE -> MaterialTheme.colorScheme.error
                }
                // Skipped-day cells override the face fill with the
                // dedicated warm brick red at FULL strength — past unmarked
                // days otherwise render dimmed exactly like future cells,
                // which is how skipped dates blended in (worst under long
                // "/dr" windows). Face colors keep their meaning everywhere
                // else (today/future overdue stays error, today's deadline
                // day stays warning).
                val fill = if (day.missed) {
                    extended.habitMissed
                } else {
                    faceColor.copy(alpha = if (day.dimmed) 0.30f else 1f)
                }
                val description = remember(day) { habitDayLabel(day) }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(
                            color = fill,
                            shape = RoundedCornerShape(2.dp)
                        )
                        .then(
                            if (day.isToday) {
                                Modifier.border(
                                    width = 1.5.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = RoundedCornerShape(2.dp)
                                )
                            } else {
                                Modifier
                            }
                        )
                        .semantics { contentDescription = description },
                    contentAlignment = Alignment.Center
                ) {
                    if (day.done) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(MaterialTheme.colorScheme.onSurface, CircleShape)
                        )
                    } else if (day.missed) {
                        HabitMissedCross(modifier = Modifier.size(8.dp))
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            days.forEach { day ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    if (day.isToday) {
                        val markerColor = MaterialTheme.colorScheme.onSurface
                        Canvas(modifier = Modifier.size(width = 8.dp, height = 5.dp)) {
                            val triangle = Path().apply {
                                moveTo(size.width / 2f, 0f)
                                lineTo(size.width, size.height)
                                lineTo(0f, size.height)
                                close()
                            }
                            drawPath(triangle, markerColor)
                        }
                    }
                }
            }
        }
        // Cutoff chip on its OWN usable full-width line: sharing the legend
        // row below clipped it to its prefix at phone width (legends +
        // weighted spacer + weighted summary left no room, maxLines=1 cut
        // the date and countdown). On this line the full
        // 截止/窗口截止 + ISO date + 距截止 N 天 renders — one line when it
        // fits, a sane two-line wrap at narrow width / larger font scale.
        // Real DEADLINE wins (截止); else a genuine /dr repeater window
        // shows its end date (窗口截止); plain habits with neither render
        // nothing here — never fabricated.
        cutoff?.let {
            HabitDeadlineChip(
                cutoff = it,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            HabitLegendSwatch(color = extended.info, label = "early")
            HabitLegendSwatch(color = extended.success, label = "due")
            HabitLegendSwatch(color = extended.warning, label = "deadline")
            HabitLegendSwatch(color = MaterialTheme.colorScheme.error, label = "overdue")
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "habit ${habit.srType}${habit.srDays}d · done $doneCount · missed $missedCount · today: " +
                    (todayDay?.let { habitStatusLabel(it) } ?: "?"),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        // Marker legend on its OWN compact row: appending "done"/"missed"
        // entries to the face row above would crowd out its trailing summary
        // (and must never push back into the cutoff chip's own line). Two
        // tiny entries only — the row stays one line high at every width.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            HabitLegendMarker(label = "done", missed = false)
            HabitLegendMarker(label = "missed", missed = true)
        }
    }
}

/**
 * Countdown chip for a habit row's effective cutoff: the real DEADLINE
 * date (截止) or, with no DEADLINE but a genuine repeater "/Nd" window,
 * its end date (窗口截止) — the same effectiveDeadline the graph's
 * current cycle uses. Renders the date + calendar-day countdown
 * (距截止 N 天 / 今天截止 / 已逾期 N 天) VISIBLY: placed on its own
 * full-width line by the caller, wrap-content up to that width and a
 * two-line wrap before any ellipsis, so narrow phones and larger font
 * scales never clip it back to the prefix. a11y carries the full text.
 */
@Composable
private fun HabitDeadlineChip(
    cutoff: HabitCutoff,
    modifier: Modifier = Modifier
) {
    val today = remember { LocalDate.now() }
    val countdown = remember(cutoff, today) { cutoff.countdown }
    val color = when (countdown.proximity) {
        HabitDeadlineProximity.UPCOMING -> LocalExtendedColors.current.info
        HabitDeadlineProximity.TODAY -> LocalExtendedColors.current.warning
        HabitDeadlineProximity.OVERDUE -> MaterialTheme.colorScheme.error
    }
    val prefix = when (cutoff.source) {
        HabitCutoffSource.DEADLINE -> "截止"
        HabitCutoffSource.REPEATER_WINDOW -> "窗口截止"
    }
    val isoDate = cutoff.date.format(DateTimeFormatter.ISO_DATE)
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.14f),
        contentColor = color,
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "$prefix $isoDate，${countdown.label}"
        }
    ) {
        Text(
            text = "$prefix $isoDate · ${countdown.label}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
            fontWeight = FontWeight.SemiBold,
            // Own-line chip: fits one line at normal scale; wraps instead
            // of clipping when the line is narrow or fonts are scaled up.
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/** One legend entry: small color swatch + label. */
@Composable
private fun HabitLegendSwatch(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Dedicated MISSED fill — a warm brick red deliberately deeper than the
 * error face (which keeps marking today/future overdue schedule
 * status) and unmistakably far from the green due face and the subdued
 * dim cells. Rendered at full strength (never dimmed) so a past expired
 * deadline day can no longer pass for a future one. Theme-driven via
 * ExtendedColors.habitMissed (Classic keeps the historic #8C1D1A; the
 * widget's baked widget_habit_missed drawable keeps that hex in BOTH
 * themes — shapes and colors of the widget cells are unchanged).
 */

/**
 * Core ink of the missed × and the done dot's widget twin; theme-driven via
 * ExtendedColors.habitMarkerInk (Classic keeps the historic #172624).
 */

/**
 * Two-tone × for missed cells: a white halo stroke under a dark core
 * stroke, the same trick as the widget's today outline — readable on the
 * brick red fill, on any face color, in light or dark themes.
 */
@Composable
private fun HabitMissedCross(modifier: Modifier = Modifier) {
    // Ink captured in the composable scope — the Canvas lambda below is a
    // DrawScope and must not read composition locals itself.
    val ink = LocalExtendedColors.current.habitMarkerInk
    Canvas(modifier = modifier) {
        val halo = 2.2.dp.toPx()
        val core = 1.1.dp.toPx()
        val arm = size.minDimension / 2f - halo / 2f
        val cx = size.width / 2f
        val cy = size.height / 2f
        val strokes = listOf(
            Offset(cx - arm, cy - arm) to Offset(cx + arm, cy + arm),
            Offset(cx + arm, cy - arm) to Offset(cx - arm, cy + arm)
        )
        strokes.forEach { (start, end) ->
            drawLine(Color.White, start, end, strokeWidth = halo, cap = StrokeCap.Round)
            drawLine(ink, start, end, strokeWidth = core, cap = StrokeCap.Round)
        }
    }
}

/**
 * Legend entry for a cell MARKER on a representative cell background:
 * "done" = the 5dp dot, "missed" = the brick-red fill plus its × — so the
 * legend shows the exact treatment the strip uses, not an abstraction.
 */
@Composable
private fun HabitLegendMarker(label: String, missed: Boolean) {
    val extended = LocalExtendedColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(
                    color = if (missed) extended.habitMissed else extendedDimInfo(),
                    shape = RoundedCornerShape(2.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (missed) {
                HabitMissedCross(modifier = Modifier.size(8.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .background(MaterialTheme.colorScheme.onSurface, CircleShape)
                )
            }
        }
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** How a dimmed neutral cell looks — the backdrop for the done legend dot. */
@Composable
private fun extendedDimInfo(): Color =
    LocalExtendedColors.current.info.copy(alpha = 0.30f)

/** Accessible per-day label: date, today marker, done/missed/status. */
private fun habitDayLabel(day: OrgHabit.Day): String {
    val date = day.date.format(HABIT_DAY_DATE)
    val whenText = if (day.isToday) "$date (today)" else date
    val stateText = when {
        day.done -> "done"
        day.missed -> "missed"
        else -> habitStatusLabel(day)
    }
    return "$whenText: $stateText"
}

private fun habitStatusLabel(day: OrgHabit.Day): String = when (day.face) {
    OrgHabit.Face.CLEAR -> "not due yet"
    OrgHabit.Face.READY -> "due"
    OrgHabit.Face.ALERT -> "deadline day"
    OrgHabit.Face.OVERDUE -> "overdue"
}

/**
 * Editable TODO keyword affordance: tapping the row's visible state chip (or
 * the ghost ＋ chip on keyword-less rows) opens the keyword picker directly —
 * no detour through the action sheet.
 */

/**
 * Keyword picker: every parser-supported TODO keyword plus an explicit
 * clear choice. Picking applies immediately through the verified write path —
 * except WAIT, Emacs's note-taking keyword (w@): picking it for a heading not
 * yet WAIT routes through [onPickWait]'s 等待原因 prompt first, and picking
 * the already-applied WAIT opens the current note's editor ([onEditWait])
 * instead of a no-op write.
 */
@Composable
private fun TodoKeywordDialog(
    entry: OrgAgendaEntry,
    busy: Boolean,
    onPick: (String) -> Unit,
    onPickWait: () -> Unit,
    onEditWait: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("TODO 状态") },
        text = {
            Column {
                Text(
                    text = entry.title.ifBlank { "(untitled)" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = entry.fileName,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                )
                if (busy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = "正在写入…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // Shared parser list with the two daily-driver states
                    // promoted to the front; chunked rows keep the dialog narrow.
                    TODO_DIALOG_KEYWORDS.chunked(KEYWORDS_PER_ROW)
                        .forEach { rowKeywords ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                rowKeywords.forEach { keyword ->
                                    val selected = entry.todo == keyword
                                    Surface(
                                        onClick = {
                                            when {
                                                // w@ note prompt before any mutation.
                                                keyword == "WAIT" && entry.todo != "WAIT" ->
                                                    onPickWait()
                                                // Already WAIT: the current note's editor.
                                                keyword == "WAIT" -> onEditWait()
                                                else -> onPick(keyword)
                                            }
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerLow
                                        },
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outlineVariant
                                        ),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier.padding(vertical = 6.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = keyword,
                                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = OrgMono),
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (selected) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    if (entry.todo != null) {
                        TextButton(onClick = onClear, modifier = Modifier.padding(top = 4.dp)) {
                            Text("清除状态（设为无关键字）")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        }
    )
}

/**
 * WAIT 等待原因 editor — the app's twin of Emacs's `w@` note prompt.
 *
 * Fresh transition (target not WAIT yet): a meaningful reason is required;
 * Save writes keyword + LOGBOOK state-note in ONE guarded write, Cancel
 * changes nothing (no keyword, no file).
 *
 * Existing WAIT item (chip re-tap or row-summary tap): the field opens with
 * the full current note — multiline — and Save rewrites only that note's
 * content; clearing the text and saving removes it (empty-prompt shape).
 * No success dialog: the write is acknowledged by the transient snackbar.
 */
@Composable
private fun WaitReasonDialog(
    entry: OrgAgendaEntry,
    busy: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val isTransition = entry.todo != "WAIT"
    var reason by remember(entry.widgetAgendaEntryKey()) {
        mutableStateOf(entry.waitReason.orEmpty())
    }
    val trimmed = reason.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("等待原因") },
        text = {
            Column {
                Text(
                    text = entry.title.ifBlank { "(untitled)" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = entry.fileName,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("等待原因") },
                    placeholder = { Text("在等什么 / 在等谁（可多行）") },
                    minLines = 2,
                    maxLines = 4,
                    isError = isTransition && trimmed.isEmpty(),
                    supportingText = when {
                        isTransition && trimmed.isEmpty() ->
                            ({ Text("请填写等待原因后再保存") })
                        !isTransition && trimmed.isEmpty() ->
                            ({ Text("留空保存将清除当前等待原因") })
                        else -> null
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (busy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = "正在写入…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(reason) },
                enabled = !busy && (!isTransition || trimmed.isNotEmpty())
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        }
    )
}

/**
 * Right-aligned mono planning chip: deadline on error tint, scheduled plain,
 * plain timestamp otherwise. Display-only on the row (the SCHEDULED editor is
 * the row's Schedule button; the full editor opens from the action sheet).
 * Rows without any planning/timestamp info render nothing here — the old
 * inert "—" placeholder chip is gone.
 */
@Composable
private fun EntryTimeChip(entry: OrgAgendaEntry) {
    // Time-of-day appended when the stamp carries one, so a precise
    // SCHEDULED/DEADLINE is visible right on the row after the edit.
    val (text, urgent) = when {
        entry.deadline != null -> entry.deadline.withTime(entry.deadlineTime).let { it to true }
        entry.scheduled != null -> entry.scheduled.withTime(entry.scheduledTime).let { it to false }
        entry.timestamp != null -> entry.timestamp.format(SHORT_DATE).let { it to false }
        // No planning info at all: render nothing (no placeholder chip).
        else -> return
    }
    val prefix = when {
        entry.deadline != null -> "D: "
        entry.scheduled != null -> "S: "
        else -> ""
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (urgent) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = prefix + text,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = OrgMono),
                color = if (urgent) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun AgendaError(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onDismiss) {
            Text("Dismiss")
        }
    }
}

internal data class AgendaSection(
    val title: String,
    val entries: List<OrgAgendaEntry>,
    val emptyText: String,
    /** GTD Projects / GTD Areas: entries are branch roots rendered with descendants. */
    val hierarchical: Boolean = false
)

internal fun OrgAgenda.sectionsFor(mode: AgendaViewMode): List<AgendaSection> {
    // Section order and headers mirror the Doom org-agenda-custom-commands
    // ("d" Daily Dashboard / "w" Weekly Review); filters mirror the builder.
    return when (mode) {
        AgendaViewMode.DAILY -> listOf(
            AgendaSection("Today", daily.today, "No planned items for today"),
            AgendaSection("Next actions", daily.nextActions, "No unplanned next actions"),
            AgendaSection("Vibing", daily.vibing, "No vibing items"),
            AgendaSection("Sandbagging", daily.sandbagging, "No unplanned sandbagging items"),
            AgendaSection("Waiting / follow-up", daily.waiting, "No unplanned waiting items"),
            AgendaSection("Done", daily.done, "No done items"),
            AgendaSection("Cancelled / dropped", daily.cancelledDropped, "No cancelled or dropped items"),
            AgendaSection("Inbox to clarify", daily.inbox, "Inbox is empty")
        )

        AgendaViewMode.WEEKLY -> listOf(
            AgendaSection("Next 14 days", weekly.nextDays, "No planned items in the next 14 days"),
            AgendaSection("Stuck Projects", weekly.stuckProjects, "No stuck projects"),
            AgendaSection("Vibing", weekly.vibing, "No vibing items"),
            AgendaSection("Sandbagging", weekly.sandbagging, "No sandbagging items"),
            AgendaSection("Waiting", weekly.waiting, "No waiting items"),
            AgendaSection("On hold", weekly.hold, "No hold items"),
            AgendaSection("Someday / Maybe", weekly.maybe, "No someday items"),
            AgendaSection("Inbox", weekly.inbox, "Inbox is empty")
        )

        AgendaViewMode.PROJECTS -> listOf(
            AgendaSection(
                "GTD Projects", projectControl.projects, "No projects",
                hierarchical = true
            ),
            AgendaSection("Stuck Projects", projectControl.stuckProjects, "No stuck projects")
        )

        AgendaViewMode.AREAS -> listOf(
            // Same tree presentation as GTD Projects: AREA roots render with
            // their full descendant branches (indentation, collapse/expand,
            // per-heading keyword editing and quick actions).
            AgendaSection(
                "GTD Areas", areaControl.areaRoots, "No areas",
                hierarchical = true
            ),
            AgendaSection("Neglected Areas", areaControl.neglectedAreas, "No neglected areas")
        )
    }
}

private val HEADER_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM dd")
private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM dd")
private val SHORT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** `MMM dd` plus ` HH:mm` when the planning stamp carries a time of day. */
private fun LocalDate.withTime(time: java.time.LocalTime?): String =
    format(SHORT_DATE) + (time?.let { " ${it.format(SHORT_TIME)}" } ?: "")
private val HABIT_DAY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
private const val KEYWORDS_PER_ROW = 3

/**
 * Parser TODO sequence with DONE and NEXT promoted to the first two slots —
 * the agenda's most frequent state changes — everything else keeps its
 * configured order. Display order only; parsing semantics still derive from
 * [OrgAgendaParser.TODO_KEYWORDS_ORDERED].
 */
private val TODO_DIALOG_KEYWORDS =
    listOf("DONE", "NEXT") + OrgAgendaParser.TODO_KEYWORDS_ORDERED.filterNot {
        it == "DONE" || it == "NEXT"
    }

private val AgendaViewMode.label: String
    get() = when (this) {
        AgendaViewMode.DAILY -> "Daily"
        AgendaViewMode.WEEKLY -> "Weekly"
        AgendaViewMode.PROJECTS -> "Projects"
        AgendaViewMode.AREAS -> "Areas"
    }

private fun OrgAgendaEntry.widgetAgendaEntryKey(): String =
    "$fileName|$sourceOffset|$titleOffset"

private fun OrgAgendaEntry.flattenVisibleAgendaEntries(): List<OrgAgendaEntry> =
    listOf(this) + children.flatMap { it.flattenVisibleAgendaEntries() }
