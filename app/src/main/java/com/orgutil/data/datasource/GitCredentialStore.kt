package com.orgutil.data.datasource

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores git remote credentials (HTTPS username + token) in an
 * [EncryptedSharedPreferences] file, falling back to plain preferences if the
 * Android Keystore is unavailable on the device.
 */
@Singleton
class GitCredentialStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val fallbackPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    fun store(username: String, token: String) {
        prefs().edit {
            putString(KEY_USERNAME, username.trim())
            putString(KEY_TOKEN, token.trim())
        }
    }

    /** Returns (username, token) or null if either has not been stored. */
    fun getCredentials(): Pair<String, String>? {
        val username = prefs().getString(KEY_USERNAME, null)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val token = prefs().getString(KEY_TOKEN, null)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return username to token
    }

    fun clear() {
        prefs().edit {
            remove(KEY_USERNAME)
            remove(KEY_TOKEN)
        }
    }

    private fun prefs(): SharedPreferences = securePrefs ?: fallbackPrefs

    companion object {
        private const val PREFS_NAME = "org_util_prefs"
        private const val SECURE_PREFS_FILE = "git_sync_secure_prefs"
        private const val KEY_USERNAME = "git_username"
        private const val KEY_TOKEN = "git_token"
    }
}
