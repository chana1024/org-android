package com.orgutil.data.gcal

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Calendar sync settings, kept in the shared app preferences file like
 * [com.orgutil.data.datasource.GitSyncConfigStore]. Holds no secrets - the
 * short-lived access token lives in [GcalCredentialStore].
 */
@Singleton
class GcalConfigStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True once authorization succeeded at least once; cleared on disconnect. */
    fun isAuthorized(): Boolean = prefs.getBoolean(KEY_AUTHORIZED, false)

    fun setAuthorized(value: Boolean) {
        prefs.edit { putBoolean(KEY_AUTHORIZED, value) }
    }

    /** Auto-sync is opt-in (a Google account grant is required). */
    fun isAutoSyncEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_SYNC, false)

    fun setAutoSyncEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_SYNC, enabled) }
    }

    fun getLastSyncTime(): Long = prefs.getLong(KEY_LAST_SYNC, 0L)

    fun setLastSyncTime(epochMs: Long) {
        prefs.edit { putLong(KEY_LAST_SYNC, epochMs) }
    }

    companion object {
        private const val PREFS_NAME = "org_util_prefs"
        private const val KEY_AUTHORIZED = "gcal_authorized"
        private const val KEY_AUTO_SYNC = "gcal_auto_sync_enabled"
        private const val KEY_LAST_SYNC = "gcal_last_sync_epoch_ms"
    }
}
