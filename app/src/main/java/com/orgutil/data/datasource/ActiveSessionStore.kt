package com.orgutil.data.datasource

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists which chat session is active (plain prefs - not a secret).
 * Survives restarts so [com.orgutil.domain.chat.ActiveSessionResolver] can
 * restore the exact session the user left, not just the newest one.
 */
@Singleton
class ActiveSessionStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(): String? = prefs.getString(KEY_ACTIVE_SESSION, null)?.takeIf { it.isNotEmpty() }

    fun set(sessionId: String) {
        prefs.edit().putString(KEY_ACTIVE_SESSION, sessionId).apply()
    }

    /** Clear only when the stored id matches (a concurrent switch wins). */
    fun clearIf(sessionId: String) {
        if (get() == sessionId) prefs.edit().remove(KEY_ACTIVE_SESSION).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_ACTIVE_SESSION).apply()
    }

    companion object {
        private const val PREFS_NAME = "chat_session_prefs"
        private const val KEY_ACTIVE_SESSION = "active_session_id"
    }
}
