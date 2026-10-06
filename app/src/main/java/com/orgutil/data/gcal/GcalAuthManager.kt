package com.orgutil.data.gcal

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a Google Identity Services authorization attempt. */
sealed interface GcalTokenResult {
    data class Token(val accessToken: String) : GcalTokenResult

    /** Consent UI is required; only the foreground (Sync screen) may launch [pendingIntent]. */
    data class ConsentNeeded(val pendingIntent: PendingIntent) : GcalTokenResult

    data class Error(val message: String) : GcalTokenResult
}

/**
 * Google Calendar authorization via Google Identity Services
 * (AuthorizationClient - never the deprecated GoogleAuthUtil). Requests only
 * the calendar.events scope; access tokens are short-lived, cached encrypted
 * in [GcalCredentialStore], and never logged. No client secret, refresh
 * token or any desktop credential ever reaches the APK.
 *
 * Background callers ([acquireTokenSilently]) never launch UI: when consent
 * is required the caller records an authorization-required status and the
 * Sync screen relaunches it in the foreground via [authorize].
 */
@Singleton
class GcalAuthManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val credentialStore: GcalCredentialStore,
    private val apiClient: GcalApiClient
) {
    /** The narrowest workable scope: read/write events on the primary calendar. */
    private val authorizationRequest: AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(CALENDAR_EVENTS_SCOPE)))
        .build()

    fun isPlayServicesAvailable(): Boolean =
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS

    /**
     * Cached token if still valid, else a silent authorization attempt
     * (application context, no consent UI). Returns [GcalTokenResult.ConsentNeeded]
     * or an [GcalTokenResult.Error] when foreground action is required.
     */
    suspend fun acquireTokenSilently(): GcalTokenResult {
        credentialStore.validToken()?.let { return GcalTokenResult.Token(it) }
        if (!isPlayServicesAvailable()) {
            return GcalTokenResult.Error("Google Play services is not available on this device")
        }
        val result = runCatching {
            Identity.getAuthorizationClient(appContext).authorize(authorizationRequest).await()
        }.getOrElse { e ->
            return GcalTokenResult.Error(authErrorMessage(e))
        }
        return result.toTokenResult()
    }

    /**
     * Foreground authorization with an Activity context: returns the consent
     * [PendingIntent][GcalTokenResult.ConsentNeeded] when user interaction is
     * required, otherwise a fresh token. The Sync screen launches the consent
     * intent and forwards the result to [tokenFromIntent].
     */
    suspend fun authorize(activityContext: Context): GcalTokenResult {
        if (!isPlayServicesAvailable()) {
            return GcalTokenResult.Error("Google Play services is not available on this device")
        }
        val result = runCatching {
            Identity.getAuthorizationClient(activityContext).authorize(authorizationRequest).await()
        }.getOrElse { e ->
            return GcalTokenResult.Error(authErrorMessage(e))
        }
        return result.toTokenResult()
    }

    /** Access token from the consent result Intent, or null when the flow ended without one. */
    fun tokenFromIntent(intent: Intent): String? = runCatching {
        Identity.getAuthorizationClient(appContext)
            .getAuthorizationResultFromIntent(intent)
            .accessToken
    }.getOrNull()

    /** Drops the cached access token (e.g. after the API rejects it with 401). */
    fun clearTokenCache() = credentialStore.clear()

    /**
     * Revokes the app's Google grant. Best effort: the caller clears local
     * state and stops scheduled work regardless; returns null on success or a
     * message the UI can show (with a myaccount.google.com fallback hint).
     */
    suspend fun revokeGrant(activityContext: Context): String? {
        val direct = runCatching {
            Identity.getAuthorizationClient(activityContext)
                .revokeAccess(RevokeAccessRequest.builder().build())
                .await()
        }
        if (direct.isSuccess) return null

        // Fallback: RFC 7009 revoke with the freshest token we can still get.
        val token = (acquireTokenSilently() as? GcalTokenResult.Token)?.accessToken
        val revoked = token != null && runCatching { apiClient.revokeToken(token) }.isSuccess
        credentialStore.clear()
        return if (revoked) {
            null
        } else {
            "Could not fully revoke Google access automatically (${authErrorMessage(direct.exceptionOrNull())}). " +
                "You can also remove \"Org-util\" at myaccount.google.com/connections."
        }
    }

    private fun AuthorizationResult.toTokenResult(): GcalTokenResult {
        val token = accessToken
        if (!token.isNullOrBlank()) {
            credentialStore.store(token, System.currentTimeMillis() + GcalCredentialStore.TOKEN_LIFETIME_MS)
            return GcalTokenResult.Token(token)
        }
        val consent = pendingIntent
        return if (hasResolution() && consent != null) {
            GcalTokenResult.ConsentNeeded(consent)
        } else {
            GcalTokenResult.Error("Google returned neither an access token nor a consent request")
        }
    }

    private fun authErrorMessage(e: Throwable?): String {
        val statusCode = runCatching {
            (e as? com.google.android.gms.common.api.ApiException)?.statusCode
        }.getOrNull()
        val reason = statusCode?.let { CommonStatusCodes.getStatusCodeString(it) }
            ?: e?.message
            ?: e?.javaClass?.simpleName
            ?: "unknown error"
        return "Google authorization failed ($reason)"
    }

    companion object {
        const val CALENDAR_EVENTS_SCOPE = "https://www.googleapis.com/auth/calendar.events"
    }
}
