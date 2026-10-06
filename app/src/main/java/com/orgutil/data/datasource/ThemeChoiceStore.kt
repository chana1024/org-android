package com.orgutil.data.datasource

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import com.orgutil.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Selectable app+widget color theme. CLASSIC is the historical
 * "OrgUtil Teal Light" palette and the default for every fresh install and
 * every missing/corrupt persisted value — nothing about its rendering may
 * change. KRAFT_LEDGER is the 牛皮账本 kraft-paper palette.
 *
 * The persisted name is a plain lowercase string resolved with an explicit
 * when-branch (never `valueOf` on raw input): an unknown value — hand-edited
 * prefs, a future removed id — falls back to CLASSIC instead of crashing the
 * app startup or the widget's remote process.
 */
enum class ThemeChoice(
    val persistName: String,
    @StringRes val labelRes: Int
) {
    CLASSIC("classic", R.string.theme_name_classic),
    KRAFT_LEDGER("kraft", R.string.theme_name_kraft);

    companion object {
        /** Strict resolver: unknown/blank/null → CLASSIC (F4 failure guard). */
        fun fromName(name: String?): ThemeChoice = when (name?.trim()?.lowercase()) {
            KRAFT_LEDGER.persistName -> KRAFT_LEDGER
            else -> CLASSIC
        }
    }
}

/**
 * Single persisted source of the theme choice, shared by the Compose app
 * ([com.orgutil.ui.theme.ThemeController]) and the AppWidget renderers
 * (Agenda widget provider/factory, Quick Capture widget), which construct it
 * directly from a context because broadcast-driven updates run without a
 * Hilt graph. Same preferences file and key for both sides — one choice,
 * everywhere, immediately after any write.
 */
@Singleton
class ThemeChoiceStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun readChoice(): ThemeChoice = ThemeChoice.fromName(
        preferences.getString(KEY_CHOICE, null)
    )

    fun writeChoice(choice: ThemeChoice) {
        preferences.edit().putString(KEY_CHOICE, choice.persistName).apply()
    }

    companion object {
        const val PREFERENCES = "theme_prefs"
        const val KEY_CHOICE = "selected_theme"
    }
}
