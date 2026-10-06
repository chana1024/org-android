package com.orgutil.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Sends cheap, explicit refresh requests only while an Agenda widget exists. */
@Singleton
class AgendaWidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun refreshAgenda() = request(AgendaWidgetProvider.ACTION_REFRESH_AGENDA)

    fun refreshPomodoro() = request(AgendaWidgetProvider.ACTION_REFRESH_POMODORO)

    private fun request(action: String) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(AgendaWidgetProvider.component(context))
        if (ids.isEmpty()) return
        context.sendBroadcast(
            Intent(context, AgendaWidgetProvider::class.java).setAction(action)
        )
    }
}
