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
 * Stores the LLM API key + model id for the agent harness in an
 * [EncryptedSharedPreferences] file (same fallback strategy as
 * [GitCredentialStore]).
 */
@Singleton
class ChatCredentialStore @Inject constructor(
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
            null
        }
    }

    fun storeApiKey(key: String) {
        prefs().edit { putString(KEY_API_KEY, key.trim()) }
    }

    fun getApiKey(): String? =
        prefs().getString(KEY_API_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun getModel(): String =
        prefs().getString(KEY_MODEL, null)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_MODEL

    fun storeModel(model: String) {
        prefs().edit { putString(KEY_MODEL, model.trim()) }
    }

    fun isConfigured(): Boolean = getApiKey() != null

    private fun prefs(): SharedPreferences = securePrefs ?: fallbackPrefs

    companion object {
        private const val PREFS_NAME = "org_util_prefs"
        private const val SECURE_PREFS_FILE = "chat_secure_prefs"
        private const val KEY_API_KEY = "chat_llm_api_key"
        private const val KEY_MODEL = "chat_llm_model"
        const val DEFAULT_MODEL = "claude-sonnet-5"
    }
}
