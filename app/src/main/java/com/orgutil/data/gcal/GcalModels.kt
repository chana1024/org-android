package com.orgutil.data.gcal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Google Calendar event payload exactly as the Doom sync builds it
 * (my/org-gtd-gcal-entry-event): summary = heading, description carries the
 * source file / outline path / Org ID / raw planning timestamp, start+end use
 * dateTime + timeZone Asia/Shanghai, and private extended properties identify
 * the event as managed by this integration (org_source=org-android).
 */
@Serializable
data class GcalEventPayload(
    val summary: String,
    val description: String,
    val start: GcalEventDateTime,
    val end: GcalEventDateTime,
    val extendedProperties: GcalExtendedProperties
)

@Serializable
data class GcalEventDateTime(
    val dateTime: String,
    val timeZone: String
)

@Serializable
data class GcalExtendedProperties(
    val private: GcalPrivateProperties
)

@Serializable
data class GcalPrivateProperties(
    val org_id: String,
    val org_source: String
)

/** Persisted association between an Org ID and the Google event this integration created for it. */
@Serializable
data class GcalSyncStateItem(
    @SerialName("org_id") val orgId: String,
    @SerialName("google_event_id") val googleEventId: String,
    @SerialName("payload_hash") val payloadHash: String,
    @SerialName("synced_at") val syncedAt: String,
    val summary: String
)

/** Result row of the last finished sync, kept for the Sync screen. */
@Serializable
data class GcalLastResult(
    val at: String,
    val scanned: Int = 0,
    val tracked: Int = 0,
    val created: Int = 0,
    val updated: Int = 0,
    val deleted: Int = 0,
    val unchanged: Int = 0,
    val failed: Int = 0,
    /** First failure / abort reason of the run, if any. */
    val error: String? = null,
    val needsAuthorization: Boolean = false,
    val warnings: List<String> = emptyList()
) {
    val detail: String
        get() = buildString {
            append("$tracked tracked")
            if (created > 0) append(" · $created created")
            if (updated > 0) append(" · $updated updated")
            if (deleted > 0) append(" · $deleted deleted")
            if (unchanged > 0) append(" · $unchanged unchanged")
            if (failed > 0) append(" · $failed failed")
        }
}

/**
 * Local sync state file (mirrors sync-state.json of the Doom implementation).
 * Written atomically after every successful Google Calendar operation so a
 * partially-failed run can be retried safely.
 */
@Serializable
data class GcalSyncState(
    val version: Int = 1,
    @SerialName("calendar_id") val calendarId: String = "primary",
    @SerialName("last_sync_at") val lastSyncAt: String? = null,
    @SerialName("last_result") val lastResult: GcalLastResult? = null,
    val items: List<GcalSyncStateItem> = emptyList()
) {
    fun itemFor(orgId: String): GcalSyncStateItem? =
        items.firstOrNull { it.orgId == orgId }
}
