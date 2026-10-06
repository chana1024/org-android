package com.orgutil.data.gcal

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Caches the short-lived Google Calendar access token (valid ~1 hour) in an
 * [EncryptedSharedPreferences] file so the 30-minute periodic sync can reuse
 * a still-valid token without another Play services round-trip. No OAuth
 * refresh token is ever stored: Google Identity Services on Android hands
 * out access tokens only, and consent re-launching stays a foreground
 * concern. Tokens are never logged.
 */
@Singleton
class GcalCredentialStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val fallbackPrefs: SharedPreferences =
        context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)

    private val securePrefs: SharedPreferences? by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            // Keystore unavailable (some devices / backup restores); degrade to plain prefs.
            null
        }
    }

    /** Returns the cached token when it has more than [minRemainingMs] left, else null. */
    fun validToken(minRemainingMs: Long = MIN_VALID_REMAINING_MS): String? {
        val token = prefs().getString(KEY_TOKEN, null)?.takeIf { it.isNotEmpty() } ?: return null
        val expiresAt = prefs().getLong(KEY_EXPIRES_AT, 0L)
        return if (System.currentTimeMillis() < expiresAt - minRemainingMs) token else null
    }

    fun store(token: String, expiresAtMs: Long) {
        prefs().edit {
            putString(KEY_TOKEN, token)
            putLong(KEY_EXPIRES_AT, expiresAtMs)
        }
    }

    fun clear() {
        prefs().edit {
            remove(KEY_TOKEN)
            remove(KEY_EXPIRES_AT)
        }
    }

    private fun prefs(): SharedPreferences = securePrefs ?: fallbackPrefs

    companion object {
        private const val FALLBACK_PREFS_NAME = "gcal_prefs"
        private const val SECURE_PREFS_FILE = "gcal_secure_prefs"
        private const val KEY_TOKEN = "gcal_access_token"
        private const val KEY_EXPIRES_AT = "gcal_token_expires_at_ms"
        private const val MIN_VALID_REMAINING_MS = 2 * 60 * 1000L

        /** Conservative lifetime: docs say access tokens live about an hour. */
        const val TOKEN_LIFETIME_MS = 50 * 60 * 1000L
    }
}
