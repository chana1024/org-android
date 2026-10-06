package com.orgutil.data.datasource

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Shared last-selected Agenda tab for the app and its home-screen widget. */
@Singleton
class AgendaViewModeStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun readModeName(): String = preferences.getString(KEY_MODE, null) ?: DEFAULT_MODE

    fun writeModeName(modeName: String) {
        preferences.edit().putString(KEY_MODE, modeName).apply()
    }

    private companion object {
        const val PREFERENCES = "agenda_widget"
        const val KEY_MODE = "selected_mode"
        const val DEFAULT_MODE = "DAILY"
    }
}
