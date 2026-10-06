package com.orgutil.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orgutil.data.repository.GtdArchivePlan
import com.orgutil.data.repository.GtdArchiveService
import com.orgutil.data.repository.OrgClockTarget
import com.orgutil.data.repository.OrgHeadingStyleService
import com.orgutil.data.repository.OrgPlanningDateService
import com.orgutil.data.repository.OrgTodoKeywordService
import com.orgutil.data.repository.PlanningDateEdit
import com.orgutil.data.datasource.AgendaViewModeStore
import com.orgutil.domain.agenda.OrgAgendaEntry
import com.orgutil.domain.files.OrgFileChangeNotifier
import com.orgutil.domain.usecase.GetOrgAgendaUseCase
import com.orgutil.pomodoro.PomodoroController
import com.orgutil.pomodoro.PomodoroSession
import com.orgutil.pomodoro.PomodoroSettings
import com.orgutil.widget.AgendaWidgetUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

@HiltViewModel
class AgendaViewModel @Inject constructor(
    private val getOrgAgendaUseCase: GetOrgAgendaUseCase,
    private val archiveService: GtdArchiveService,
    private val todoKeywordService: OrgTodoKeywordService,
    private val headingStyleService: OrgHeadingStyleService,
    private val planningDateService: OrgPlanningDateService,
    private val pomodoroController: PomodoroController,
    fileChangeNotifier: OrgFileChangeNotifier,
    private val modeStore: AgendaViewModeStore,
    private val widgetUpdater: AgendaWidgetUpdater
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AgendaUiState(isLoading = true, selectedMode = modeStore.readModeName().toAgendaViewMode())
    )
    val uiState: StateFlow<AgendaUiState> = _uiState.asStateFlow()

    /**
     * One-shot success acknowledgements (archive / TODO / habit / planning /
     * pomodoro) rendered as transient snackbars. A channel, not state: each
     * message is consumed exactly once, never replays on recomposition or tab
     * re-entry, and a message raised while the screen is away is buffered
     * instead of lost. Failures do NOT go here — they stay in [AgendaUiState]
     * so their detail dialog stays until dismissed.
     */
    private val _successNotices = Channel<String>(Channel.BUFFERED)
    val successNotices: Flow<String> = _successNotices.receiveAsFlow()

    /** Live pomodoro session (null = idle); remaining time derives from it. */
    val pomodoro: StateFlow<PomodoroSession?> = pomodoroController.state

    private var refreshJob: Job? = null

    /** The plan backing the confirmation dialog; null when no run is staged. */
    private var pendingArchive: GtdArchivePlan? = null

    init {
        refresh()
        observeFileChanges(fileChangeNotifier)
        // Process death / reboot recovery: advance expired phases (clock-out
        // included) or reschedule the pending alarm.
        pomodoroController.recover()
    }

    fun setMode(mode: AgendaViewMode) {
        if (_uiState.value.selectedMode == mode) return
        modeStore.writeModeName(mode.name)
        _uiState.value = _uiState.value.copy(selectedMode = mode)
        widgetUpdater.refreshAgenda()
    }

    fun onAgendaOpened() {
        widgetUpdater.refreshAgenda()
    }

    /**
     * Reloads the agenda. With [showLoading] false the current content stays
     * on screen (loading only when there is nothing to show yet) - used for
     * file-change events and tab re-entry so the list does not flicker.
     */
    fun refresh(showLoading: Boolean = true) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            if (showLoading || _uiState.value.agenda == null) {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            }
            getOrgAgendaUseCase()
                .onSuccess { agenda ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        agenda = agenda,
                        error = null
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message ?: "Failed to load agenda"
                    )
                }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /** Read-only scan for DONE subtrees; nothing is written before confirm. */
    fun prepareArchive() {
        if (_uiState.value.archiveBusy) return
        _uiState.update { it.copy(archiveBusy = true, archiveNotice = null) }
        viewModelScope.launch {
            archiveService.plan()
                .onSuccess { plan ->
                    pendingArchive = plan
                    _uiState.update { state ->
                        state.copy(
                            archiveBusy = false,
                            archivePlan = ArchivePlanUi(
                                count = plan.items.size,
                                totalChars = plan.items.sumOf { it.subtreeText.length },
                                preview = plan.items
                                    .take(ARCHIVE_PREVIEW_ITEMS)
                                    .map { "${it.fileName} · ${it.title.ifBlank { "(untitled)" }}" },
                                moreCount = (plan.items.size - ARCHIVE_PREVIEW_ITEMS).coerceAtLeast(0)
                            )
                        )
                    }
                }
                .onFailure { e ->
                    pendingArchive = null
                    _uiState.update {
                        it.copy(
                            archiveBusy = false,
                            archiveNotice = e.message ?: "Failed to plan the archive"
                        )
                    }
                }
        }
    }

    fun dismissArchivePlan() {
        pendingArchive = null
        _uiState.update { it.copy(archivePlan = null) }
    }

    /**
     * Runs the confirmed plan. The service re-verifies the files, backs up
     * originals, writes the destination first and rolls back on failure; the
     * resulting SAF writes fire the file-change notifier, which reloads the
     * agenda (archived entries disappear from every section).
     */
    fun confirmArchive() {
        val plan = pendingArchive ?: return
        if (_uiState.value.archiveBusy) return
        _uiState.update { it.copy(archiveBusy = true, archivePlan = null) }
        viewModelScope.launch {
            archiveService.execute(plan)
                .onSuccess { count ->
                    pendingArchive = null
                    _uiState.update { it.copy(archiveBusy = false) }
                    _successNotices.trySend("Archived $count DONE item(s) to ${plan.destinationPath}")
                    refresh(showLoading = false)
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            archiveBusy = false,
                            archiveNotice = e.message ?: "Archive failed"
                        )
                    }
                }
        }
    }

    fun dismissArchiveNotice() {
        _uiState.update { it.copy(archiveNotice = null) }
    }

    // ---- TODO keyword editing ----

    /** Opens the keyword picker for one agenda entry (row tap still opens the file). */
    fun editTodoKeyword(entry: OrgAgendaEntry) {
        if (_uiState.value.todoEditBusy) return
        _uiState.update { it.copy(todoEditTarget = entry) }
    }

    fun dismissTodoEdit() {
        if (_uiState.value.todoEditBusy) return
        _uiState.update { it.copy(todoEditTarget = null) }
    }

    /**
     * Applies the picked keyword (null = clear) through the verified
     * repository write path, then reloads so sections and the statistics
     * card immediately reflect the change.
     */
    fun applyTodoKeyword(keyword: String?) {
        val target = _uiState.value.todoEditTarget ?: return
        if (_uiState.value.todoEditBusy) return
        _uiState.update { it.copy(todoEditBusy = true) }
        viewModelScope.launch {
            todoKeywordService.setTodoKeyword(target, keyword)
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            todoEditBusy = false,
                            todoEditTarget = null
                        )
                    }
                    _successNotices.trySend(
                        "已将「${target.title.ifBlank { "(untitled)" }}」状态更新为 ${keyword ?: "无"}"
                    )
                    refresh(showLoading = false)
                }
                .onFailure { e ->
                    _uiState.update { state ->
                        state.copy(
                            todoEditBusy = false,
                            todoNotice = e.message ?: "更新 TODO 状态失败"
                        )
                    }
                }
        }
    }

    fun dismissTodoNotice() {
        _uiState.update { it.copy(todoNotice = null) }
    }

    // ---- WAIT 等待原因 (WAIT(w@) explanatory note) ----

    /**
     * WAIT picked in the keyword dialog for a heading not yet WAIT: swap the
     * picker for the reason prompt BEFORE any mutation — Emacs's `w@` note
     * prompt. Cancelling the prompt changes nothing (no keyword, no file).
     */
    fun requestWaitReason() {
        val target = _uiState.value.todoEditTarget ?: return
        if (_uiState.value.todoEditBusy || _uiState.value.waitReasonBusy) return
        _uiState.update { it.copy(todoEditTarget = null, waitReasonTarget = target) }
    }

    /**
     * Opens the reason editor for a heading that already IS WAIT (chip
     * re-tap in the picker, or the row's subdued reason summary): shows the
     * full current note and saves edits through the verified write path. A
     * WAIT heading whose latest transition has no note starts empty — the
     * add-reason affordance — never placeholder noise.
     */
    fun editWaitReason(entry: OrgAgendaEntry) {
        if (entry.todo != "WAIT") return
        if (_uiState.value.waitReasonBusy) return
        _uiState.update { it.copy(todoEditTarget = null, waitReasonTarget = entry) }
    }

    fun dismissWaitReason() {
        if (_uiState.value.waitReasonBusy) return
        _uiState.update { it.copy(waitReasonTarget = null) }
    }

    /**
     * Saves the prompted reason: a fresh WAIT transition writes the keyword
     * plus its LOGBOOK state-note in one guarded write; an existing WAIT
     * item rewrites only the latest note. Success is a transient snackbar
     * (no OK dialog); failure lands in the waitNotice dialog.
     */
    fun saveWaitReason(reason: String) {
        val target = _uiState.value.waitReasonTarget ?: return
        if (_uiState.value.waitReasonBusy) return
        val isTransition = target.todo != "WAIT"
        val trimmed = reason.trim()
        if (isTransition && trimmed.isEmpty()) return
        _uiState.update { it.copy(waitReasonBusy = true) }
        viewModelScope.launch {
            val write = if (isTransition) {
                todoKeywordService.setTodoKeyword(target, "WAIT", trimmed)
            } else {
                todoKeywordService.editWaitReason(target, trimmed)
            }
            write.onSuccess {
                _uiState.update { state ->
                    state.copy(waitReasonBusy = false, waitReasonTarget = null)
                }
                _successNotices.trySend(
                    if (isTransition) WAIT_TRANSITION_SET else WAIT_REASON_SAVED
                )
                refresh(showLoading = false)
            }.onFailure { e ->
                _uiState.update { state ->
                    state.copy(
                        waitReasonBusy = false,
                        waitNotice = e.message ?: WAIT_WRITE_FAILED
                    )
                }
            }
        }
    }

    fun dismissWaitNotice() {
        _uiState.update { it.copy(waitNotice = null) }
    }

    // ---- Habit (STYLE=habit) editing ----

    /**
     * Habit affordance for one agenda entry: an active habit is turned off
     * directly (STYLE=habit removed, SCHEDULED preserved); anything else with
     * a valid source identity opens the explicit schedule setup dialog.
     */
    fun editHabit(entry: OrgAgendaEntry) {
        if (_uiState.value.habitBusy) return
        if (entry.habit != null) {
            removeHabitStyle(entry)
        } else if (entry.sourceOffset >= 0 && entry.titleOffset >= 0) {
            _uiState.update { it.copy(habitEditTarget = entry) }
        }
    }

    fun dismissHabitEdit() {
        if (_uiState.value.habitBusy) return
        _uiState.update { it.copy(habitEditTarget = null) }
    }

    /**
     * Applies the picked schedule through the source-identity-verified write
     * path (fresh read, surgical subtree edit, dual-parser validation,
     * read-back), then reloads so the habit graph and sections reflect it.
     */
    fun applyHabitSchedule(schedule: OrgHeadingStyleService.HabitSchedule) {
        val target = _uiState.value.habitEditTarget ?: return
        runHabitWrite(target, HABIT_SET) {
            headingStyleService.enableHabit(target, schedule)
        }
    }

    /** Removes only STYLE=habit; the SCHEDULED planning stays untouched. */
    fun removeHabitStyle(entry: OrgAgendaEntry) {
        if (_uiState.value.habitBusy) return
        runHabitWrite(entry, HABIT_REMOVED) {
            headingStyleService.disableHabitStyle(entry)
        }
    }

    fun dismissHabitNotice() {
        _uiState.update { it.copy(habitNotice = null) }
    }

    private fun runHabitWrite(
        target: OrgAgendaEntry,
        successNotice: String,
        write: suspend () -> Result<Unit>
    ) {
        if (_uiState.value.habitBusy) return
        _uiState.update { it.copy(habitBusy = true) }
        viewModelScope.launch {
            write()
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            habitBusy = false,
                            habitEditTarget = null
                        )
                    }
                    _successNotices.trySend(successNotice)
                    refresh(showLoading = false)
                }
                .onFailure { e ->
                    _uiState.update { state ->
                        state.copy(
                            habitBusy = false,
                            habitNotice = e.message ?: HABIT_WRITE_FAILED
                        )
                    }
                }
        }
    }

    // ---- planning date (SCHEDULED / DEADLINE) editing ----

    /**
     * Opens the planning-date editor for one agenda entry (row tap still
     * opens the file). Only entries with a valid source identity can be
     * edited; the write path re-verifies it against a fresh read.
     */
    fun editPlanningDates(entry: OrgAgendaEntry) {
        if (_uiState.value.planningBusy) return
        if (entry.sourceOffset < 0 || entry.titleOffset < 0) return
        _uiState.update { it.copy(planningEditTarget = entry) }
    }

    fun dismissPlanningEdit() {
        if (_uiState.value.planningBusy) return
        _uiState.update { it.copy(planningEditTarget = null) }
    }

    /**
     * Applies the picked SCHEDULED/DEADLINE choices (each independently set
     * or clear) through the source-identity-verified write path (fresh read,
     * surgical subtree edit, full re-parse validation, read-back), then
     * reloads so sections and the date chip immediately reflect it.
     */
    fun applyPlanningDates(scheduled: PlanningDateEdit, deadline: PlanningDateEdit) {
        val target = _uiState.value.planningEditTarget ?: return
        if (_uiState.value.planningBusy) return
        _uiState.update { it.copy(planningBusy = true) }
        viewModelScope.launch {
            planningDateService.setPlanningDates(target, scheduled, deadline)
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            planningBusy = false,
                            planningEditTarget = null
                        )
                    }
                    _successNotices.trySend(PLANNING_SET)
                    refresh(showLoading = false)
                }
                .onFailure { e ->
                    _uiState.update { state ->
                        state.copy(
                            planningBusy = false,
                            planningNotice = e.message ?: PLANNING_WRITE_FAILED
                        )
                    }
                }
        }
    }

    fun dismissPlanningNotice() {
        _uiState.update { it.copy(planningNotice = null) }
    }

    // ---- SCHEDULED-only quick action ----

    /**
     * Opens the row's SCHEDULED quick editor for one agenda entry. Only the
     * scheduled stamp is edited there; the write below passes [PlanningDateEdit.Keep]
     * for DEADLINE so its bytes are provably left alone.
     */
    fun editSchedule(entry: OrgAgendaEntry) {
        if (_uiState.value.planningBusy) return
        if (entry.sourceOffset < 0 || entry.titleOffset < 0) return
        _uiState.update { it.copy(scheduleEditTarget = entry) }
    }

    fun dismissScheduleEdit() {
        if (_uiState.value.planningBusy) return
        _uiState.update { it.copy(scheduleEditTarget = null) }
    }

    /**
     * Writes only the SCHEDULED stamp through the same source-identity-verified
     * write path; DEADLINE is kept as parsed, repeaters and windows included.
     */
    fun applyScheduleOnly(date: LocalDate, time: LocalTime) {
        val target = _uiState.value.scheduleEditTarget ?: return
        if (_uiState.value.planningBusy) return
        _uiState.update { it.copy(planningBusy = true) }
        viewModelScope.launch {
            planningDateService.setPlanningDates(
                target,
                PlanningDateEdit.SetDate(date, time),
                PlanningDateEdit.Keep
            )
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            planningBusy = false,
                            scheduleEditTarget = null
                        )
                    }
                    _successNotices.trySend(SCHEDULE_SET)
                    refresh(showLoading = false)
                }
                .onFailure { e ->
                    _uiState.update { state ->
                        state.copy(
                            planningBusy = false,
                            planningNotice = e.message ?: PLANNING_WRITE_FAILED
                        )
                    }
                }
        }
    }

    // ---- pomodoro ----

    /**
     * Starts a focus session on one agenda entry: clocks the heading in
     * (standard Org CLOCK in its LOGBOOK, through the verified surgical write
     * path), schedules the single phase-end alarm and posts the status
     * notification. Durations come from [PomodoroSettings] (Doom defaults:
     * 40 focus / 5 short / 20 long every 4th).
     */
    fun startPomodoro(entry: OrgAgendaEntry) {
        if (_uiState.value.pomodoroBusy) return
        if (entry.sourceOffset < 0 || entry.titleOffset < 0) return
        _uiState.update { it.copy(pomodoroBusy = true, pomodoroNotice = null) }
        viewModelScope.launch {
            pomodoroController.start(OrgClockTarget.from(entry), entry.title)
                .onSuccess {
                    _uiState.update { state -> state.copy(pomodoroBusy = false) }
                    _successNotices.trySend(
                        "已开始专注 ${pomodoroController.pomodoroSettings().focusMinutes} 分钟（CLOCK 已写入）"
                    )
                }
                .onFailure { e ->
                    _uiState.update { state ->
                        state.copy(
                            pomodoroBusy = false,
                            pomodoroNotice = e.message ?: POMODORO_START_FAILED
                        )
                    }
                }
        }
    }

    /** Stops the running session (clocks out mid-focus) — overlay/Stop button. */
    fun stopPomodoro() {
        pomodoroController.stop()
    }

    fun savePomodoroSettings(settings: PomodoroSettings) {
        pomodoroController.savePomodoroSettings(settings)
    }

    fun pomodoroSettings(): PomodoroSettings = pomodoroController.pomodoroSettings()

    // Special-permission probes for the start dialog (recomputed per
    // recomposition so returning from Settings refreshes the rows).
    fun canScheduleExactAlarms(): Boolean = pomodoroController.canScheduleExact()

    fun notificationsEnabled(): Boolean = pomodoroController.notificationsGranted()

    fun dismissPomodoroNotice() {
        _uiState.update { it.copy(pomodoroNotice = null) }
    }

    private fun observeFileChanges(fileChangeNotifier: OrgFileChangeNotifier) {
        viewModelScope.launch {
            fileChangeNotifier.changes
                // Debounce without @FlowPreview: a new emission cancels the
                // pending reload, coalescing bursts (e.g. agent write sessions).
                .collectLatest {
                    delay(FILE_CHANGE_DEBOUNCE_MS)
                    refresh(showLoading = false)
                }
        }
    }

    private companion object {
        const val FILE_CHANGE_DEBOUNCE_MS = 300L
        const val ARCHIVE_PREVIEW_ITEMS = 12

        const val HABIT_SET =
            "已写入 STYLE=habit 与带重复周期的 SCHEDULED 计划"
        const val HABIT_REMOVED =
            "已移除该标题的 STYLE=habit（SCHEDULED 计划保持不变）"
        const val HABIT_WRITE_FAILED = "更新习惯属性失败"

        const val PLANNING_SET =
            "已更新该条目的计划日期（SCHEDULED / DEADLINE）"
        const val SCHEDULE_SET =
            "已更新 SCHEDULED（DEADLINE 保持不变）"
        const val PLANNING_WRITE_FAILED = "更新计划日期失败"

        const val WAIT_TRANSITION_SET =
            "已设为 WAIT，并把等待原因写入 LOGBOOK"
        const val WAIT_REASON_SAVED =
            "已更新等待原因（历史记录保持不变）"
        const val WAIT_WRITE_FAILED = "写入等待原因失败"

        const val POMODORO_START_FAILED = "启动番茄钟失败"
    }
}

private fun String.toAgendaViewMode(): AgendaViewMode =
    runCatching { AgendaViewMode.valueOf(this) }.getOrDefault(AgendaViewMode.DAILY)
