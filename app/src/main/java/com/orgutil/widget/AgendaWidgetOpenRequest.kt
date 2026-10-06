package com.orgutil.widget

import android.content.Intent
import android.os.SystemClock

data class AgendaWidgetOpenRequest(
    val requestId: Long,
    val modeName: String?,
    val fileName: String?,
    val fileUri: String?,
    val sourceOffset: Int?,
    val entryTitle: String?,
    val sectionTitle: String?,
    /** Row TODO-badge tap: open the entry's TODO keyword chooser on arrival. */
    val openTodoChooser: Boolean = false,
    /** Row timer-chip tap: open the entry's pomodoro start dialog on arrival. */
    val openPomodoro: Boolean = false,
    /** Header 总目标-button tap: open the agenda's goal detail sheet on arrival. */
    val openGoal: Boolean = false
)

fun Intent.toAgendaWidgetOpenRequest(): AgendaWidgetOpenRequest? {
    if (getStringExtra(AgendaWidgetProvider.EXTRA_NAVIGATE_TO) != "agenda") return null
    return AgendaWidgetOpenRequest(
        requestId = getLongExtra(EXTRA_REQUEST_ID, SystemClock.elapsedRealtimeNanos()),
        modeName = getStringExtra(AgendaWidgetProvider.EXTRA_WIDGET_MODE),
        fileName = getStringExtra(AgendaWidgetProvider.EXTRA_WIDGET_FILE_NAME),
        fileUri = getStringExtra(AgendaWidgetProvider.EXTRA_WIDGET_FILE_URI),
        sourceOffset = if (hasExtra(AgendaWidgetProvider.EXTRA_WIDGET_SOURCE_OFFSET)) {
            getIntExtra(AgendaWidgetProvider.EXTRA_WIDGET_SOURCE_OFFSET, -1).takeIf { it >= 0 }
        } else null,
        entryTitle = getStringExtra(AgendaWidgetProvider.EXTRA_WIDGET_ENTRY_TITLE),
        sectionTitle = getStringExtra(AgendaWidgetProvider.EXTRA_WIDGET_SECTION_TITLE),
        openTodoChooser = getBooleanExtra(AgendaWidgetProvider.EXTRA_WIDGET_OPEN_TODO, false),
        openPomodoro = getBooleanExtra(AgendaWidgetProvider.EXTRA_WIDGET_OPEN_POMODORO, false),
        openGoal = getBooleanExtra(AgendaWidgetProvider.EXTRA_WIDGET_OPEN_GOAL, false)
    )
}

private const val EXTRA_REQUEST_ID = "agenda_widget_request_id"
