package com.orgutil.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.orgutil.R
import com.orgutil.data.datasource.AgendaViewModeStore
import com.orgutil.data.datasource.ThemeChoice
import com.orgutil.domain.agenda.OrgGoalStats
import com.orgutil.domain.agenda.OrgHabit
import com.orgutil.domain.agenda.HabitCutoffSource
import com.orgutil.domain.agenda.OrgAgendaEntry
import com.orgutil.domain.agenda.habitCutoff
import com.orgutil.domain.usecase.GetOrgAgendaUseCase
import com.orgutil.ui.screens.AgendaSection
import com.orgutil.ui.screens.sectionsFor
import com.orgutil.ui.viewmodel.AgendaViewMode
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal class AgendaWidgetRemoteViewsFactory(
    private val context: Context,
    private val appWidgetId: Int,
    private val getOrgAgenda: GetOrgAgendaUseCase,
    private val modeStore: AgendaViewModeStore
) : RemoteViewsService.RemoteViewsFactory {

    private sealed interface Row {
        val stableId: Long
        data class Section(val section: AgendaSection, override val stableId: Long) : Row
        data class Entry(
            val entry: OrgAgendaEntry,
            val sectionTitle: String,
            val depth: Int,
            override val stableId: Long
        ) : Row
        data class Message(val text: String, override val stableId: Long) : Row
    }

    private var rows: List<Row> = emptyList()
    private var loadedModeName: String = "DAILY"
    private var loadedMode: AgendaViewMode = AgendaViewMode.DAILY

    /**
     * Palette snapshot taken when the factory binds and refreshed on every
     * onDataSetChanged — the same persisted choice the app wrote, so rows
     * always render in the current theme (a theme change triggers the full
     * refresh broadcast → notifyAppWidgetViewDataChanged → here).
     */
    private var themeChoice: ThemeChoice = WidgetTheme.choice(context)

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        themeChoice = WidgetTheme.choice(context)
        rows = try {
            val agenda = runBlocking { getOrgAgenda().getOrThrow() }
            loadedModeName = modeStore.readModeName()
            loadedMode = runCatching { AgendaViewMode.valueOf(loadedModeName) }
                .getOrDefault(AgendaViewMode.DAILY)
            // Same single vault scan as the rows — the header stats pill is
            // pushed as ONE partial update per data change, never re-scanned
            // per cell and never computed on the main thread.
            pushGoalStats(agenda.goalStats)
            buildRows(agenda.sectionsFor(loadedMode))
        } catch (error: Exception) {
            // A failed scan must never leave the previous totals standing
            // as if current: invalidate the persisted line AND the view.
            invalidateGoalStats()
            listOf(Row.Message(
                error.message?.takeIf(String::isNotBlank) ?: "Agenda unavailable. Tap refresh to retry.",
                ERROR_ROW_ID
            ))
        }
    }

    override fun onDestroy() {
        rows = emptyList()
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews? {
        val row = rows.getOrNull(position) ?: return null
        return when (row) {
            is Row.Section -> RemoteViews(context.packageName, WidgetTheme.sectionLayout(themeChoice)).apply {
                setTextViewText(R.id.widget_agenda_section_title, row.section.title)
                setTextViewText(R.id.widget_agenda_section_count, row.section.entries.size.toString())
            }
            is Row.Entry -> RemoteViews(context.packageName, WidgetTheme.entryLayout(themeChoice)).apply {
                val entry = row.entry
                setTextViewText(R.id.widget_agenda_entry_todo, entry.todo ?: "•")
                // Per-keyword badge colors, mirroring the in-app OrgStateChip
                // palette for the CURRENT theme. Both background and text
                // color are set EXPLICITLY on every row (unknown/null
                // included) so RemoteViews recycling can never leak a
                // previous row's state colors.
                // setInt("setBackgroundResource", …) with a baked drawable —
                // no bitmap is generated per badge.
                val badge = WidgetTodoBadge.forKeyword(entry.todo, themeChoice)
                setInt(R.id.widget_agenda_entry_todo, "setBackgroundResource", badge.backgroundRes)
                setTextColor(R.id.widget_agenda_entry_todo, badge.textColor)
                setTextViewText(R.id.widget_agenda_entry_title, entry.title)
                setTextViewText(R.id.widget_agenda_entry_date, planningLabel(entry))
                setTextViewText(R.id.widget_agenda_entry_indent, if (row.depth > 0) "  ".repeat(row.depth) + "↳" else "")
                // WAIT 等待原因 summary: the same parsed note the in-app row
                // shows, capped at the layout's two subdued lines. Only
                // first-class RemoteViews ops (memory: stub android.jar strips
                // @RemotableViewMethod); GONE by default so non-WAIT rows and
                // note-less WAIT rows keep their exact previous height — no
                // placeholder noise, no card ballooning. Row taps ride the
                // root fill-in below, which lands on this entry in the app.
                val waitReason = entry.waitReason?.takeIf { entry.todo == "WAIT" && it.isNotBlank() }
                setViewVisibility(
                    R.id.widget_agenda_entry_wait_reason,
                    if (waitReason != null) android.view.View.VISIBLE else android.view.View.GONE
                )
                if (waitReason != null) {
                    setTextViewText(R.id.widget_agenda_entry_wait_reason, waitReason)
                }
                // Same visibility rule as AgendaScreen's showHabitGraph:
                // org-habit-show-habits-only-for-today puts the consistency
                // graph on current-day habit rows in the daily view only.
                val habit = entry.habit
                val showHabitGraph = habit != null &&
                    loadedMode == AgendaViewMode.DAILY &&
                    row.sectionTitle == TODAY_SECTION
                setViewVisibility(
                    R.id.widget_agenda_habit_graph,
                    if (showHabitGraph) android.view.View.VISIBLE else android.view.View.GONE
                )
                setViewVisibility(
                    R.id.widget_agenda_habit_summary,
                    if (showHabitGraph) android.view.View.VISIBLE else android.view.View.GONE
                )
                if (showHabitGraph && habit != null) {
                    // Cutoff priority mirrors the Agenda row: real DEADLINE
                    // stamp first; without one, a genuine repeater "/Nd"
                    // window's effectiveDeadline (the same date the graph's
                    // current cycle ALERT day uses).
                    renderHabitGraph(habit.buildGraph(LocalDate.now()), habit, entry.deadline)
                }
                val fillIn = Intent()
                    .putExtra(AgendaWidgetProvider.EXTRA_WIDGET_MODE, loadedModeName)
                    .putExtra(AgendaWidgetProvider.EXTRA_WIDGET_FILE_NAME, entry.fileName)
                    .putExtra(AgendaWidgetProvider.EXTRA_WIDGET_FILE_URI, entry.uri.toString())
                    .putExtra(AgendaWidgetProvider.EXTRA_WIDGET_SOURCE_OFFSET, entry.sourceOffset)
                    .putExtra(AgendaWidgetProvider.EXTRA_WIDGET_ENTRY_TITLE, entry.title)
                    .putExtra(AgendaWidgetProvider.EXTRA_WIDGET_SECTION_TITLE, row.sectionTitle)
                setOnClickFillInIntent(R.id.widget_agenda_entry_root, fillIn)
                // The TODO badge is the keyword picker trigger, mirroring the
                // Agenda row's state chip: its fill-in flags the app to open
                // the existing TodoKeywordDialog on this exact entry.
                val todoFillIn = Intent(fillIn)
                    .putExtra(AgendaWidgetProvider.EXTRA_WIDGET_OPEN_TODO, true)
                setOnClickFillInIntent(R.id.widget_agenda_entry_todo, todoFillIn)
                // Trailing timer chip opens the app's PomodoroStartDialog for
                // this entry — its own flag, so it stays disjoint from the
                // root open-editor and the TODO chooser above.
                val pomodoroFillIn = Intent(fillIn)
                    .putExtra(AgendaWidgetProvider.EXTRA_WIDGET_OPEN_POMODORO, true)
                setOnClickFillInIntent(R.id.widget_agenda_entry_pomodoro, pomodoroFillIn)
            }
            is Row.Message -> RemoteViews(context.packageName, WidgetTheme.emptyLayout(themeChoice)).apply {
                setTextViewText(R.id.widget_agenda_empty_text, row.text)
            }
        }
    }

    override fun getLoadingView(): RemoteViews? =
        RemoteViews(context.packageName, WidgetTheme.emptyLayout(themeChoice)).apply {
            setTextViewText(R.id.widget_agenda_empty_text, "Loading Agenda…")
        }

    /**
     * View-type CAPACITY, not the per-render count. The widget host's
     * RemoteViewsAdapter maps each DISTINCT layout resource to an internal
     * type id (loading view is type 0; metadata is sized getViewTypeCount()
     * + 1 — see AOSP RemoteViewsAdapter.updateTemporaryMetaData /
     * getMappedViewType). The SAME factory instance (same adapter identity)
     * can serve BOTH palettes across data changes: a theme switch fires
     * onDataSetChanged and getViewAt then returns the kraft twins while the
     * adapter still maps the classic ones — worst case 3 classic + 3 kraft
     * distinct row layouts. Beyond the declared count, isViewTypeInRange
     * fails and the host DROPS those rows (log: "returns more view types
     * than indicated by getViewTypeCount()") rather than crash the hosting
     * AdapterView — i.e. rows would silently disappear after a toggle.
     * Declaring 6 covers both families; over-declaring is safe (capacity,
     * not an exact count). The loading view reuses the ACTIVE theme's empty
     * layout, so it adds no row type of its own.
     */
    override fun getViewTypeCount(): Int = 6

    override fun getItemId(position: Int): Long = rows.getOrNull(position)?.stableId ?: position.toLong()

    override fun hasStableIds(): Boolean = true

    /**
     * Header 总目标 pill — the SAME [OrgGoalStats] snapshot the Agenda
     * stats card renders (今日已完成 n/m: today's integrated T/N/V/W
     * population — Today, Next actions, Vibing, Waiting — plus tasks and
     * habits completed TODAY; the pill abbreviates the label for width, the
     * full labelled form goes into the pill's content description together
     * with its 打开总目标 role).
     * Purely formatting: every number comes from the shared model, never
     * re-derived here. The line is persisted TOGETHER WITH ITS SCAN DATE
     * so the provider can re-apply it on full updates without a scan —
     * but never across a day boundary (date-dependent numbers go stale at
     * midnight); the partial update rides this factory's own data change,
     * so a refresh always re-lands fresh numbers.
     */
    private fun pushGoalStats(stats: OrgGoalStats) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val line = "${stats.taskDone}/${stats.taskTotal}"
        val description = "打开总目标 — 今日已完成 ${stats.taskDone}/${stats.taskTotal}"
        context.getSharedPreferences(AgendaWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(AgendaWidgetProvider.PREF_GOAL_STATS_LINE, line)
            .putString(AgendaWidgetProvider.PREF_GOAL_STATS_DESCRIPTION, description)
            .putString(AgendaWidgetProvider.PREF_GOAL_STATS_DATE, LocalDate.now().toString())
            .apply()
        val views = RemoteViews(context.packageName, WidgetTheme.headerLayout(themeChoice))
        views.setTextViewText(R.id.widget_agenda_goal_stats, line)
        views.setContentDescription(R.id.widget_agenda_goal, description)
        AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(appWidgetId, views)
    }

    /** Failed scan: blank the persisted line and mark the pill unavailable. */
    private fun invalidateGoalStats() {
        context.getSharedPreferences(AgendaWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(AgendaWidgetProvider.PREF_GOAL_STATS_LINE)
            .remove(AgendaWidgetProvider.PREF_GOAL_STATS_DESCRIPTION)
            .remove(AgendaWidgetProvider.PREF_GOAL_STATS_DATE)
            .apply()
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val views = RemoteViews(context.packageName, WidgetTheme.headerLayout(themeChoice))
        views.setTextViewText(R.id.widget_agenda_goal_stats, "")
        views.setContentDescription(R.id.widget_agenda_goal, "总目标进度不可用")
        AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(appWidgetId, views)
    }

    private fun buildRows(sections: List<AgendaSection>): List<Row> = buildList {
        sections.forEach { section ->
            add(Row.Section(section, "section:${section.title}".stableId()))
            val entries = if (section.hierarchical) {
                section.entries.flatMap { flatten(it, section.title, 0) }
            } else {
                section.entries.map { Row.Entry(it, section.title, 0, entryStableId(it, section.title)) }
            }
            if (entries.isEmpty()) {
                add(Row.Message(section.emptyText, "empty:${section.title}".stableId()))
            } else {
                addAll(entries)
            }
        }
    }

    private fun flatten(entry: OrgAgendaEntry, sectionTitle: String, depth: Int): List<Row.Entry> =
        listOf(Row.Entry(entry, sectionTitle, depth, entryStableId(entry, sectionTitle))) +
            entry.children.flatMap { flatten(it, sectionTitle, depth + 1) }

    private fun entryStableId(entry: OrgAgendaEntry, sectionTitle: String): Long =
        "${sectionTitle}:${entry.fileName}:${entry.sourceOffset}:${entry.titleOffset}".stableId()

    private fun String.stableId(): Long = hashCode().toLong()

    private fun planningLabel(entry: OrgAgendaEntry): String {
        val date = entry.scheduled ?: entry.deadline ?: entry.timestamp ?: return ""
        val time = entry.scheduledTime ?: entry.deadlineTime
        return date.format(DATE_FORMAT) + (time?.let { " · ${it.format(TIME_FORMAT)}" } ?: "")
    }

    /**
     * Widget twin of the Agenda's HabitConsistencyGraph: one weighted cell
     * per day, face color as the cell (dimmed past/future days at the same
     * 30%-over-white the app's alpha 0.30 yields on the white card), a
     * centered dot overlay for done days, and for today a full-cell
     * two-tone outline plus a caret cue in the row beneath the graph —
     * the 5dp ring used before was illegible at widget scale. SKIPPED
     * repetition days (strictly past, cadence called for a completion,
     * none recorded — daily: every undone day of the recorded active
     * interval; period: only rewind-reconstructed due dates with no
     * completion in their window/slot) swap the face fill for the dedicated
     * brick-red widget_habit_missed cell PLUS the two-tone × marker,
     * exactly like the in-app graph — so a skipped day can never pass for a
     * dim future cell in either surface, no matter how long the "/dr"
     * window is. Future and rest days are never marked. Only
     * launcher-safe RemoteViews operations
     * are used (setImageViewResource / setViewVisibility /
     * setContentDescription) — no reflection actions.
     */
    private fun RemoteViews.renderHabitGraph(
        days: List<OrgHabit.Day>,
        habit: OrgHabit,
        deadline: LocalDate?
    ) {
        days.forEachIndexed { index, day ->
            val cellId = CELL_IDS[index]
            setImageViewResource(cellId, cellDrawable(day))
            // Per-day spoken state, mirroring AgendaScreen.habitDayLabel.
            setContentDescription(cellId, habitDayContentDescription(day))
            val markerId = MARKER_IDS[index]
            when {
                day.isToday && day.done -> {
                    setViewVisibility(markerId, android.view.View.VISIBLE)
                    setImageViewResource(markerId, R.drawable.widget_habit_marker_today_done)
                }
                day.isToday -> {
                    setViewVisibility(markerId, android.view.View.VISIBLE)
                    setImageViewResource(markerId, R.drawable.widget_habit_marker_today)
                }
                day.done -> {
                    setViewVisibility(markerId, android.view.View.VISIBLE)
                    setImageViewResource(markerId, R.drawable.widget_habit_marker_done)
                }
                day.missed -> {
                    setViewVisibility(markerId, android.view.View.VISIBLE)
                    setImageViewResource(markerId, R.drawable.widget_habit_marker_missed)
                }
                else -> setViewVisibility(markerId, android.view.View.GONE)
            }
            // Caret cue under the TODAY cell only; slots mirror the cell
            // weights so it points at the exact outlined day.
            setViewVisibility(
                TODAY_CUE_IDS[index],
                // Keep the weighted slot in layout; GONE redistributes the
                // row width to the sole visible caret and centers it globally.
                if (day.isToday) android.view.View.VISIBLE else android.view.View.INVISIBLE
            )
        }
        // Summary mirrors the Agenda row verbatim (habitStatusLabel labels),
        // plus the same effective cutoff the in-app chip counts down to:
        // real DEADLINE (截止) or a genuine /dr repeater window end
        // (窗口截止), appended compactly (compact MM-dd date + countdown)
        // so the single summary line keeps its height.
        val todayDay = days.firstOrNull { it.isToday }
        val doneCount = days.count { it.done }
        val missedCount = days.count { it.missed }
        val cutoff = habitCutoff(deadline, habit, LocalDate.now())
        setTextViewText(
            R.id.widget_agenda_habit_summary,
            buildString {
                append("habit ${habit.srType}${habit.srDays}d · done $doneCount · missed $missedCount · today: ")
                append(todayDay?.let { habitStatusLabel(it) } ?: "?")
                cutoff?.let {
                    append(" · ")
                    append(
                        when (it.source) {
                            HabitCutoffSource.DEADLINE -> "截止"
                            HabitCutoffSource.REPEATER_WINDOW -> "窗口截止"
                        }
                    )
                    append(it.date.format(WIDGET_DATE_FORMAT))
                    append(it.countdown.label.replace(" ", ""))
                }
            }
        )
    }

    private fun cellDrawable(day: OrgHabit.Day): Int = when {
        // Missed wins over the face fill (brick red, always full strength)
        // — same override the in-app graph applies.
        day.missed -> R.drawable.widget_habit_missed
        else -> when (day.face) {
            OrgHabit.Face.CLEAR ->
                if (day.dimmed) R.drawable.widget_habit_clear_dim else R.drawable.widget_habit_clear
            OrgHabit.Face.READY ->
                if (day.dimmed) R.drawable.widget_habit_ready_dim else R.drawable.widget_habit_ready
            OrgHabit.Face.ALERT ->
                if (day.dimmed) R.drawable.widget_habit_alert_dim else R.drawable.widget_habit_alert
            OrgHabit.Face.OVERDUE ->
                if (day.dimmed) R.drawable.widget_habit_overdue_dim else R.drawable.widget_habit_overdue
        }
    }

    /**
     * AgendaScreen.habitDayLabel wording (compact MM-dd date): done /
     * missed / face status, "(today)" marker on the current day. Set as
     * the cell's contentDescription so the widget strip speaks the same
     * states the in-app row does.
     */
    private fun habitDayContentDescription(day: OrgHabit.Day): String {
        val date = day.date.format(WIDGET_DATE_FORMAT)
        val whenText = if (day.isToday) "$date (today)" else date
        val stateText = when {
            day.done -> "done"
            day.missed -> "missed"
            else -> habitStatusLabel(day)
        }
        return "$whenText: $stateText"
    }

    /** AgendaScreen.habitStatusLabel labels, kept in sync with the app row. */
    private fun habitStatusLabel(day: OrgHabit.Day): String = when (day.face) {
        OrgHabit.Face.CLEAR -> "not due yet"
        OrgHabit.Face.READY -> "due"
        OrgHabit.Face.ALERT -> "deadline day"
        OrgHabit.Face.OVERDUE -> "overdue"
    }

    private companion object {
        const val ERROR_ROW_ID = Long.MIN_VALUE
        const val TODAY_SECTION = "Today"
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /** Compact cutoff date inside the widget's single summary line. */
        val WIDGET_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")

        // One slot per graph day: 21 preceding + today + 7 following
        // (OrgHabit.PRECEDING_DAYS / FOLLOWING_DAYS defaults).
        val CELL_IDS = intArrayOf(
            R.id.widget_habit_cell_00, R.id.widget_habit_cell_01, R.id.widget_habit_cell_02,
            R.id.widget_habit_cell_03, R.id.widget_habit_cell_04, R.id.widget_habit_cell_05,
            R.id.widget_habit_cell_06, R.id.widget_habit_cell_07, R.id.widget_habit_cell_08,
            R.id.widget_habit_cell_09, R.id.widget_habit_cell_10, R.id.widget_habit_cell_11,
            R.id.widget_habit_cell_12, R.id.widget_habit_cell_13, R.id.widget_habit_cell_14,
            R.id.widget_habit_cell_15, R.id.widget_habit_cell_16, R.id.widget_habit_cell_17,
            R.id.widget_habit_cell_18, R.id.widget_habit_cell_19, R.id.widget_habit_cell_20,
            R.id.widget_habit_cell_21, R.id.widget_habit_cell_22, R.id.widget_habit_cell_23,
            R.id.widget_habit_cell_24, R.id.widget_habit_cell_25, R.id.widget_habit_cell_26,
            R.id.widget_habit_cell_27, R.id.widget_habit_cell_28
        )
        val MARKER_IDS = intArrayOf(
            R.id.widget_habit_marker_00, R.id.widget_habit_marker_01, R.id.widget_habit_marker_02,
            R.id.widget_habit_marker_03, R.id.widget_habit_marker_04, R.id.widget_habit_marker_05,
            R.id.widget_habit_marker_06, R.id.widget_habit_marker_07, R.id.widget_habit_marker_08,
            R.id.widget_habit_marker_09, R.id.widget_habit_marker_10, R.id.widget_habit_marker_11,
            R.id.widget_habit_marker_12, R.id.widget_habit_marker_13, R.id.widget_habit_marker_14,
            R.id.widget_habit_marker_15, R.id.widget_habit_marker_16, R.id.widget_habit_marker_17,
            R.id.widget_habit_marker_18, R.id.widget_habit_marker_19, R.id.widget_habit_marker_20,
            R.id.widget_habit_marker_21, R.id.widget_habit_marker_22, R.id.widget_habit_marker_23,
            R.id.widget_habit_marker_24, R.id.widget_habit_marker_25, R.id.widget_habit_marker_26,
            R.id.widget_habit_marker_27, R.id.widget_habit_marker_28
        )
        val TODAY_CUE_IDS = intArrayOf(
            R.id.widget_habit_today_cue_00, R.id.widget_habit_today_cue_01, R.id.widget_habit_today_cue_02,
            R.id.widget_habit_today_cue_03, R.id.widget_habit_today_cue_04, R.id.widget_habit_today_cue_05,
            R.id.widget_habit_today_cue_06, R.id.widget_habit_today_cue_07, R.id.widget_habit_today_cue_08,
            R.id.widget_habit_today_cue_09, R.id.widget_habit_today_cue_10, R.id.widget_habit_today_cue_11,
            R.id.widget_habit_today_cue_12, R.id.widget_habit_today_cue_13, R.id.widget_habit_today_cue_14,
            R.id.widget_habit_today_cue_15, R.id.widget_habit_today_cue_16, R.id.widget_habit_today_cue_17,
            R.id.widget_habit_today_cue_18, R.id.widget_habit_today_cue_19, R.id.widget_habit_today_cue_20,
            R.id.widget_habit_today_cue_21, R.id.widget_habit_today_cue_22, R.id.widget_habit_today_cue_23,
            R.id.widget_habit_today_cue_24, R.id.widget_habit_today_cue_25, R.id.widget_habit_today_cue_26,
            R.id.widget_habit_today_cue_27, R.id.widget_habit_today_cue_28
        )
    }
}
