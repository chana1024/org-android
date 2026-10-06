package com.orgutil.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.orgutil.data.datasource.ThemeChoice
import com.orgutil.data.datasource.ThemeChoiceStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observable holder of the live theme choice. One snapshot-state instance for
 * the whole process: [com.orgutil.MainActivity] reads [current] inside
 * `setContent` so a change made in the Settings screen recomposes the entire
 * tree under [OrgUtilTheme] on the same frame — no activity recreate needed.
 *
 * `select` persists FIRST, then flips the state, so a process death between
 * the two can only ever show the older persisted palette, never an
 * unpersisted one. Widgets are refreshed separately by the caller
 * (Settings → AgendaWidgetUpdater broadcast) because this class must stay
 * free of widget dependencies.
 */
@Singleton
class ThemeController @Inject constructor(
    private val store: ThemeChoiceStore
) {
    var current: ThemeChoice by mutableStateOf(store.readChoice())
        private set

    fun select(choice: ThemeChoice) {
        store.writeChoice(choice)
        current = choice
    }
}
