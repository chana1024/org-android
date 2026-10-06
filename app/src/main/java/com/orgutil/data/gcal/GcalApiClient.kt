package com.orgutil.data.gcal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Google Calendar API error; [code] 401/403 mean the grant/token is unusable, 404/410 mean "gone". */
class GcalApiException(
    val httpCode: Int,
    message: String
) : IllegalStateException("HTTP $httpCode: $message") {
    val isAuthError: Boolean get() = httpCode == 401 || httpCode == 403
    val isGone: Boolean get() = httpCode == 404 || httpCode == 410
}

/**
 * Thin Google Calendar v3 REST client (the Android counterpart of
 * my/org-gtd-gcal-request / -find-event-id / -insert-event / -patch-event /
 * -delete-event). Only ever touches the `primary` calendar and only events
 * addressed by this integration's own state records or org_id private
 * extended property - never a bulk list-and-delete sweep. OAuth tokens ride
 * the Authorization header and are never logged.
 */
@Singleton
class GcalApiClient @Inject constructor() {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Google event id already carrying `privateExtendedProperty org_id=<orgId>`,
     * or null (mirrors my/org-gtd-gcal-find-event-id). Lets a rebuilt local
     * state re-adopt its events instead of duplicating them.
     */
    suspend fun findEventId(accessToken: String, orgId: String): String? =
        withContext(Dispatchers.IO) {
            val url = "https://www.googleapis.com/calendar/v3".toHttpUrl().newBuilder()
                .addPathSegments("calendars/primary/events")
                .addQueryParameter("privateExtendedProperty", "org_id=$orgId")
                .addQueryParameter("singleEvents", "true")
                .addQueryParameter("maxResults", "1")
                .build()
            val body = execute(Request.Builder().url(url).authorized(accessToken).get().build())
            val items = runCatching {
                json.parseToJsonElement(body).jsonObject["items"]?.jsonArray
            }.getOrNull()
            items?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
        }

    /** Inserts [payload]; returns the created Google event id. */
    suspend fun insertEvent(accessToken: String, payloadJson: String): String =
        withContext(Dispatchers.IO) {
            val url = "https://www.googleapis.com/calendar/v3/calendars/primary/events"
                .toHttpUrl()
            val body = execute(
                Request.Builder().url(url).authorized(accessToken)
                    .post(payloadJson.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            )
            requireEventId(body)
        }

    /** Patches event [eventId] with [payloadJson]; returns the (possibly new) event id. */
    suspend fun patchEvent(accessToken: String, eventId: String, payloadJson: String): String =
        withContext(Dispatchers.IO) {
            val url = "https://www.googleapis.com/calendar/v3/calendars/primary/events/${encode(eventId)}"
                .toHttpUrl()
            val body = execute(
                Request.Builder().url(url).authorized(accessToken)
                    .patch(payloadJson.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            )
            requireEventId(body)
        }

    /** Deletes event [eventId]. A 404/410 is surfaced as [GcalApiException.isGone] for the caller to treat as done. */
    suspend fun deleteEvent(accessToken: String, eventId: String) {
        withContext(Dispatchers.IO) {
            val url = "https://www.googleapis.com/calendar/v3/calendars/primary/events/${encode(eventId)}"
                .toHttpUrl()
            execute(
                Request.Builder().url(url).authorized(accessToken).delete().build(),
                allowEmptyBody = true
            )
        }
    }

    /** RFC 7009 token revocation - revokes the grant behind [accessToken]. */
    suspend fun revokeToken(accessToken: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://oauth2.googleapis.com/revoke")
                .post(FormBody.Builder().add("token", accessToken).build())
                .build()
            execute(request, allowEmptyBody = true)
            true
        }.isSuccess
    }

    // ---- plumbing ----

    private fun Request.Builder.authorized(token: String): Request.Builder =
        header("Authorization", "Bearer $token")

    private fun encode(eventId: String): String =
        eventId.replace("/", "%2F") // Google ids may contain '/'

    private fun requireEventId(body: String): String =
        runCatching {
            json.parseToJsonElement(body).jsonObject["id"]?.jsonPrimitive?.content
        }.getOrNull()
            ?: throw GcalApiException(0, "Google Calendar response has no event id")

    private fun execute(request: Request, allowEmptyBody: Boolean = false): String {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw GcalApiException(0, "network error: ${e.message ?: e.javaClass.simpleName}")
        }
        response.use {
            val body = runCatching { it.body?.string().orEmpty() }.getOrDefault("")
            if (!it.isSuccessful) {
                throw GcalApiException(it.code, errorMessage(body, it.code))
            }
            if (body.isBlank() && !allowEmptyBody) {
                throw GcalApiException(it.code, "empty response body")
            }
            return body
        }
    }

    private fun errorMessage(body: String, code: Int): String {
        val parsed = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
                ?.get("message")?.jsonPrimitive?.content
        }.getOrNull()
        val message = parsed ?: body.take(300).ifBlank { "HTTP $code" }
        return message.take(300)
    }

    private companion object {
        val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()
    }
}
