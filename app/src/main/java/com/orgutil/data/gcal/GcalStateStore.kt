package com.orgutil.data.gcal

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local Google Calendar sync state (the Android counterpart of the Doom
 * sync-state.json). Kept as a small JSON file in app-private storage and
 * rewritten atomically after every successful Google Calendar operation, so
 * an interrupted run can be retried without duplicating or losing events.
 *
 * Items only ever record events this integration itself created or adopted
 * via the org_id private extended property - they are the only events a
 * later sync may patch or delete.
 */
@Singleton
class GcalStateStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val file = File(File(context.filesDir, "gcal"), "sync_state.json")
    private val mutex = Mutex()

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun read(): GcalSyncState = withContext(Dispatchers.IO) {
        mutex.withLock { readLocked() }
    }

    /** Applies [transform] to the current state and persists the result atomically. */
    suspend fun mutate(transform: (GcalSyncState) -> GcalSyncState): GcalSyncState =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val next = transform(readLocked())
                writeLocked(next)
                next
            }
        }

    /** Upserts [item], stamping it synced now. */
    suspend fun upsertItem(item: GcalSyncStateItem): GcalSyncState =
        mutate { state ->
            state.copy(
                items = state.items.filter { it.orgId != item.orgId } +
                    item.copy(syncedAt = nowStamp())
            )
        }

    suspend fun removeItem(orgId: String): GcalSyncState =
        mutate { state -> state.copy(items = state.items.filter { it.orgId != orgId }) }

    /** Records a finished run (mirrors my/org-gtd-gcal-mark-state-synced). */
    suspend fun markSynced(result: GcalLastResult): GcalSyncState =
        mutate { state ->
            state.copy(
                lastSyncAt = result.at,
                lastResult = result
            )
        }

    private fun readLocked(): GcalSyncState = runCatching {
        if (file.exists()) json.decodeFromString(GcalSyncState.serializer(), file.readText()) else null
    }.getOrNull() ?: GcalSyncState()

    /** Write-temp + rename so a crash mid-write can never corrupt the state. */
    private fun writeLocked(state: GcalSyncState) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(GcalSyncState.serializer(), state))
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                throw IllegalStateException("Could not persist Google Calendar sync state at ${file.absolutePath}")
            }
        }
    }

    companion object {
        private val DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        fun nowStamp(): String = OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).format(DISPLAY_FORMAT)

        fun nowEpochMs(): Long = System.currentTimeMillis()
    }
}
