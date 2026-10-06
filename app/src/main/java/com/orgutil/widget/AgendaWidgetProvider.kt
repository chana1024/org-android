package com.orgutil.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import com.orgutil.MainActivity
import com.orgutil.R
import com.orgutil.pomodoro.PomodoroPhase
import com.orgutil.pomodoro.PomodoroStore

class AgendaWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it, refreshList = true) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH_AGENDA -> {
                Toast.makeText(context, R.string.widget_refreshing, Toast.LENGTH_SHORT).show()
                refreshAll(context, refreshList = true)
            }
            ACTION_REFRESH_POMODORO -> refreshPomodoroViews(context)
        }
    }

    private fun refreshAll(context: Context, refreshList: Boolean) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(component(context))
        ids.forEach { updateWidget(context, manager, it, refreshList) }
    }

    private fun refreshPomodoroViews(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(component(context))
        // Partial updates ride the THEMED header layout (identical view IDs)
        // so a pomodoro tick never repaints a kraft widget in classic skin.
        val headerLayout = WidgetTheme.headerLayout(WidgetTheme.choice(context))
        ids.forEach { appWidgetId ->
            val timerViews = RemoteViews(context.packageName, headerLayout)
            renderPomodoro(context, timerViews)
            manager.partiallyUpdateAppWidget(appWidgetId, timerViews)
        }
    }

    companion object {
        const val ACTION_REFRESH_AGENDA = "com.orgutil.widget.REFRESH_AGENDA"
        const val ACTION_REFRESH_POMODORO = "com.orgutil.widget.REFRESH_POMODORO"

        /** Shared prefs holding the widget's persisted view state. */
        const val PREFS_NAME = "agenda_widget"

        /** Last 总目标 stats line the list factory pushed (survives full updates). */
        const val PREF_GOAL_STATS_LINE = "goal_stats_line"

        /** Full labelled form (今日已完成) for the stats strip's a11y text. */
        const val PREF_GOAL_STATS_DESCRIPTION = "goal_stats_description"

        /**
         * LocalDate (ISO) the persisted stats line was computed on. The
         * numbers are DAY-DEPENDENT (today's goal population / completions),
         * so a line from a previous day is stale and must never be re-applied
         * as if current — the pill falls back to the plain 总目标 opener
         * until the next scan lands fresh numbers.
         */
        const val PREF_GOAL_STATS_DATE = "goal_stats_date"

        const val EXTRA_NAVIGATE_TO = "navigate_to"
        const val EXTRA_WIDGET_MODE = "agenda_widget_mode"
        const val EXTRA_WIDGET_FILE_NAME = "agenda_widget_file_name"
        const val EXTRA_WIDGET_FILE_URI = "agenda_widget_file_uri"
        const val EXTRA_WIDGET_SOURCE_OFFSET = "agenda_widget_source_offset"
        const val EXTRA_WIDGET_ENTRY_TITLE = "agenda_widget_entry_title"
        const val EXTRA_WIDGET_SECTION_TITLE = "agenda_widget_section_title"
        /** Row TODO-badge fill-in flag: open the entry's TODO keyword chooser. */
        const val EXTRA_WIDGET_OPEN_TODO = "agenda_widget_open_todo"
        /** Row timer-chip fill-in flag: open the entry's pomodoro start dialog. */
        const val EXTRA_WIDGET_OPEN_POMODORO = "agenda_widget_open_pomodoro"
        /** Header 总目标-button flag: open the agenda's goal detail sheet. */
        const val EXTRA_WIDGET_OPEN_GOAL = "agenda_widget_open_goal"

        fun component(context: Context) = ComponentName(context, AgendaWidgetProvider::class.java)

        private fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int,
            refreshList: Boolean
        ) {
            // Palette resolved fresh on every full update — a refresh
            // broadcast (theme change, refresh button, launcher re-add)
            // always re-skins the widget from the persisted choice.
            val themeChoice = WidgetTheme.choice(context)
            val views = RemoteViews(context.packageName, WidgetTheme.headerLayout(themeChoice))
            val widgetPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val modeName = widgetPrefs.getString("selected_mode", "DAILY") ?: "DAILY"
            views.setTextViewText(R.id.widget_agenda_mode, modeLabel(modeName))
            // 总目标 stats strip: re-apply the last line the list factory
            // pushed so full updates never blank it while the fresh scan is
            // in flight (prefs read only — no vault I/O on this main thread).
            // Day guard: the numbers describe TODAY; a persisted line from a
            // previous day is stale at the day boundary and is dropped, never
            // shown as current (the next scan/refresh re-lands fresh ones).
            val statsLine = widgetPrefs.getString(PREF_GOAL_STATS_LINE, "") ?: ""
            val statsFresh = statsLine.isNotBlank() &&
                widgetPrefs.getString(PREF_GOAL_STATS_DATE, "") == java.time.LocalDate.now().toString()
            if (statsFresh) {
                views.setTextViewText(R.id.widget_agenda_goal_stats, statsLine)
                widgetPrefs.getString(PREF_GOAL_STATS_DESCRIPTION, "")?.takeIf { it.isNotBlank() }
                    ?.let { views.setContentDescription(R.id.widget_agenda_goal, it) }
            } else {
                // No usable snapshot (never scanned, last scan failed, or a
                // stale previous-day line): the pill reads as the plain 总目标
                // opener, never yesterday's numbers.
                views.setContentDescription(R.id.widget_agenda_goal, "打开总目标")
            }

            val openAgenda = Intent(context, MainActivity::class.java)
                .setAction("com.orgutil.widget.OPEN_AGENDA")
                .putExtra(EXTRA_NAVIGATE_TO, "agenda")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val openAgendaPending = PendingIntent.getActivity(
                context,
                30_000 + appWidgetId,
                openAgenda,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_agenda_open, openAgendaPending)

            val refreshIntent = Intent(context, AgendaWidgetProvider::class.java)
                .setAction(ACTION_REFRESH_AGENDA)
            val refreshPending = PendingIntent.getBroadcast(
                context,
                40_000 + appWidgetId,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_agenda_refresh, refreshPending)

            // 总目标 header action: opens the app straight into the agenda's
            // goal detail sheet (same sheet the stats card's goal region
            // opens). The pill doubles as the goal-statistics display, so
            // the labelled a11y text is set with the stats below/ by the
            // factory. Distinct intent action + request code so it never
            // collides with the plain open-agenda PendingIntent.
            val goalIntent = Intent(context, MainActivity::class.java)
                .setAction("com.orgutil.widget.OPEN_AGENDA_GOAL")
                .putExtra(EXTRA_NAVIGATE_TO, "agenda")
                .putExtra(EXTRA_WIDGET_OPEN_GOAL, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val goalPending = PendingIntent.getActivity(
                context,
                60_000 + appWidgetId,
                goalIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // Whole merged pill (总目标 label + stats numbers) is the target.
            views.setOnClickPendingIntent(R.id.widget_agenda_goal, goalPending)

            val serviceIntent = Intent(context, AgendaWidgetRemoteViewsService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            serviceIntent.data = Uri.parse(serviceIntent.toUri(Intent.URI_INTENT_SCHEME))
            views.setRemoteAdapter(R.id.widget_agenda_list, serviceIntent)

            val rowIntent = Intent(context, MainActivity::class.java)
                .setAction("com.orgutil.widget.OPEN_AGENDA_ITEM")
                .putExtra(EXTRA_NAVIGATE_TO, "agenda")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val rowPending = PendingIntent.getActivity(
                context,
                50_000 + appWidgetId,
                rowIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.widget_agenda_list, rowPending)

            renderPomodoro(context, views)
            manager.updateAppWidget(appWidgetId, views)
            if (refreshList) manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_agenda_list)
        }

        private fun renderPomodoro(context: Context, views: RemoteViews) {
            val session = PomodoroStore(context).readSession()
            if (session == null) {
                views.setViewVisibility(R.id.widget_pomodoro_card, View.GONE)
                return
            }
            val remainingMillis = (session.endAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            views.setViewVisibility(R.id.widget_pomodoro_card, View.VISIBLE)
            views.setTextViewText(
                R.id.widget_pomodoro_phase,
                if (session.phase == PomodoroPhase.FOCUS) "FOCUS" else "BREAK"
            )
            views.setTextViewText(R.id.widget_pomodoro_title, session.taskTitle)
            if (remainingMillis == 0L) {
                views.setViewVisibility(R.id.widget_pomodoro_timer, View.GONE)
                views.setViewVisibility(R.id.widget_pomodoro_zero, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_pomodoro_timer, View.VISIBLE)
                views.setViewVisibility(R.id.widget_pomodoro_zero, View.GONE)
                val elapsedBase = SystemClock.elapsedRealtime() + remainingMillis
                views.setChronometer(
                    R.id.widget_pomodoro_timer,
                    elapsedBase,
                    "🍅 %s",
                    true
                )
                views.setChronometerCountDown(R.id.widget_pomodoro_timer, true)
            }
        }

        private fun modeLabel(modeName: String): String = when (modeName) {
            "WEEKLY" -> "Weekly"
            "PROJECTS" -> "Projects"
            "AREAS" -> "Areas"
            else -> "Daily"
        }
    }
}
