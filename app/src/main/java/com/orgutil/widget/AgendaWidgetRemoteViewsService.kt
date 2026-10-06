package com.orgutil.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.widget.RemoteViewsService
import com.orgutil.data.datasource.AgendaViewModeStore
import com.orgutil.domain.usecase.GetOrgAgendaUseCase
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AgendaWidgetRemoteViewsService : RemoteViewsService() {

    @Inject lateinit var getOrgAgenda: GetOrgAgendaUseCase
    @Inject lateinit var modeStore: AgendaViewModeStore

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        AgendaWidgetRemoteViewsFactory(
            context = applicationContext,
            // The factory pushes the header stats strip as a partial update
            // for exactly this widget instance after each data change.
            appWidgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            ),
            getOrgAgenda = getOrgAgenda,
            modeStore = modeStore
        )
}
