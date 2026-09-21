package com.orgutil.data.datasource

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores git sync configuration in the same preferences file used by
 * [DocumentTreeStore], following the existing app persistence style.
 */
@Singleton
class GitSyncConfigStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getRemoteUrl(): String? =
        prefs.getString(GIT_REMOTE_URL_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setRemoteUrl(url: String) {
        prefs.edit { putString(GIT_REMOTE_URL_KEY, url.trim()) }
    }

    fun getRepoRootOverride(): String? =
        prefs.getString(GIT_REPO_ROOT_OVERRIDE_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setRepoRootOverride(path: String) {
        prefs.edit { putString(GIT_REPO_ROOT_OVERRIDE_KEY, path.trim()) }
    }

    fun isAutoSyncEnabled(): Boolean = prefs.getBoolean(GIT_AUTO_SYNC_KEY, true)

    fun setAutoSyncEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(GIT_AUTO_SYNC_KEY, enabled) }
    }

    fun getLastSyncTime(): Long = prefs.getLong(GIT_LAST_SYNC_KEY, 0L)

    fun setLastSyncTime(epochMs: Long) {
        prefs.edit { putLong(GIT_LAST_SYNC_KEY, epochMs) }
    }

    /** Sync is considered configured once a remote URL has been provided. */
    fun isConfigured(): Boolean = getRemoteUrl() != null

    companion object {
        private const val PREFS_NAME = "org_util_prefs"
        private const val GIT_REMOTE_URL_KEY = "git_remote_url"
        private const val GIT_REPO_ROOT_OVERRIDE_KEY = "git_repo_root_override"
        private const val GIT_AUTO_SYNC_KEY = "git_auto_sync_enabled"
        private const val GIT_LAST_SYNC_KEY = "git_last_sync_epoch_ms"
    }
}
