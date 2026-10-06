package com.orgutil.data.datasource

import android.content.Context
import android.content.SharedPreferences
import com.orgutil.domain.chat.AgentLoop
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User-tunable chat harness settings (plain prefs - none are secrets).
 * Budget defaults stay conservative; nothing here hardcodes an unverified
 * provider-side context limit.
 */
@Singleton
class ChatSettingsStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Input token budget for compaction + mid-run trimming. */
    fun budgetTokens(): Long = prefs.getLong(KEY_BUDGET_TOKENS, AgentLoop.DEFAULT_CONVERSATION_BUDGET_TOKENS)
        .coerceIn(MIN_BUDGET_TOKENS, MAX_BUDGET_TOKENS)

    fun setBudgetTokens(value: Long) {
        prefs.edit().putLong(KEY_BUDGET_TOKENS, value.coerceIn(MIN_BUDGET_TOKENS, MAX_BUDGET_TOKENS)).apply()
    }

    /** Recent exchanges always kept verbatim when compacting. */
    fun keepExchanges(): Int = prefs.getInt(KEY_KEEP_EXCHANGES, DEFAULT_KEEP_EXCHANGES)
        .coerceIn(1, 50)

    fun setKeepExchanges(value: Int) {
        prefs.edit().putInt(KEY_KEEP_EXCHANGES, value.coerceIn(1, 50)).apply()
    }

    /** Optional fallback provider profile id (empty = disabled). */
    fun fallbackProfileId(): String? = prefs.getString(KEY_FALLBACK_PROFILE, null)?.takeIf { it.isNotBlank() }

    fun setFallbackProfileId(profileId: String?) {
        prefs.edit().putString(KEY_FALLBACK_PROFILE, profileId).apply()
    }

    companion object {
        private const val PREFS_NAME = "chat_settings_prefs"
        private const val KEY_BUDGET_TOKENS = "budget_tokens"
        private const val KEY_KEEP_EXCHANGES = "keep_exchanges"
        private const val KEY_FALLBACK_PROFILE = "fallback_profile_id"
        const val MIN_BUDGET_TOKENS = 4_000L
        const val MAX_BUDGET_TOKENS = 200_000L
        const val DEFAULT_KEEP_EXCHANGES = 6
    }
}
