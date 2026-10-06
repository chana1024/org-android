package com.orgutil.data.gcal

import com.orgutil.domain.gcal.GcalSyncOutcome
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Terminal outcome of one engine run. */
sealed interface GcalSyncResult {
    data class Success(val outcome: GcalSyncOutcome) : GcalSyncResult
    data class NeedsAuthorization(val message: String) : GcalSyncResult
    data class Failure(val message: String) : GcalSyncResult
}

/**
 * One-way GTD -> Google Calendar sync engine (the Android port of the Doom
 * my/org-gtd-gcal-start-sync / -plan-sync / -apply-plan-async pipeline):
 *
 * 1. Acquire an access token (cache, else silent authorization). Consent is a
 *    foreground concern - here it becomes [GcalSyncResult.NeedsAuthorization].
 * 2. Scan the GTD sources. Any scan failure aborts the run: deletion only
 *    ever happens from a complete, successful scan.
 * 3. Plan create / update / delete / unchanged against the persisted local
 *    state (payload-hash comparison).
 * 4. Apply sequentially; state is persisted after EVERY successful operation,
 *    so a mid-run crash retries cleanly. Creates re-adopt an existing event
 *    via the org_id private extended property before inserting (a rebuilt
 *    state cannot duplicate events); patches of vanished events re-adopt or
 *    recreate; deletes treat 404/410 as done. Only events recorded in the
 *    local state are ever patched or deleted - never a bulk sweep.
 */
@Singleton
class GcalSyncEngine @Inject constructor(
    private val scanner: GcalOrgScanner,
    private val stateStore: GcalStateStore,
    private val apiClient: GcalApiClient,
    private val authManager: GcalAuthManager,
    private val configStore: GcalConfigStore
) {
    private val runMutex = Mutex()

    private val json = Json { encodeDefaults = true }

    suspend fun sync(): GcalSyncResult {
        if (!runMutex.tryLock()) {
            return GcalSyncResult.Failure("A Google Calendar sync is already running")
        }
        try {
            return syncLocked()
        } finally {
            runMutex.unlock()
        }
    }

    private suspend fun syncLocked(): GcalSyncResult {
        // 1. Authorization (silent only - consent is relaunched from Sync screen).
        val token = when (val t = authManager.acquireTokenSilently()) {
            is GcalTokenResult.Token -> t.accessToken
            is GcalTokenResult.ConsentNeeded -> {
                recordNeedsAuthorization("Google authorization (consent) is required - open Sync and reauthorize")
                return GcalSyncResult.NeedsAuthorization("consent required")
            }
            is GcalTokenResult.Error -> {
                recordNeedsAuthorization(t.message)
                return GcalSyncResult.NeedsAuthorization(t.message)
            }
        }
        configStore.setAuthorized(true)

        // 2. Scan. A failed/partial scan aborts before anything is planned.
        val scan = try {
            scanner.scan()
        } catch (e: GcalScanException) {
            recordFailure(e.message ?: "GTD scan failed")
            return GcalSyncResult.Failure(e.message ?: "GTD scan failed")
        }

        // 3. Plan against the persisted state.
        val state = stateStore.read()
        val warnings = scan.warnings.toMutableList()
        val creates = mutableListOf<GcalScanEvent>()
        val updates = mutableListOf<Pair<GcalScanEvent, GcalSyncStateItem>>()
        var unchanged = 0
        val currentIds = scan.events.map { it.orgId }.toSet()

        scan.events.forEach { event ->
            val item = state.itemFor(event.orgId)
            when {
                item == null || item.googleEventId.isBlank() -> creates += event
                payloadHash(event.payload) == item.payloadHash -> unchanged++
                else -> updates += event to item
            }
        }
        val deletes = state.items.filter { it.googleEventId.isNotBlank() && it.orgId !in currentIds }

        // 4. Apply; state persisted after every successful operation.
        var created = 0
        var updated = 0
        var deleted = 0
        var failed = 0
        var firstError: String? = null
        var authLost: String? = null

        suspend fun upsert(event: GcalScanEvent, knownEventId: String?, countAsCreated: Boolean): String? {
            // Adopt an existing managed event first (my/org-gtd-gcal-sync-create);
            // only insert when none is found, so a rebuilt state cannot duplicate.
            val adoptId = knownEventId ?: apiClient.findEventId(token, event.orgId)
            val id = if (adoptId != null) {
                apiClient.patchEvent(token, adoptId, payloadJson(event.payload))
            } else {
                apiClient.insertEvent(token, payloadJson(event.payload))
            }
            stateStore.upsertItem(
                GcalSyncStateItem(
                    orgId = event.orgId,
                    googleEventId = id,
                    payloadHash = payloadHash(event.payload),
                    syncedAt = "",
                    summary = event.payload.summary
                )
            )
            if (countAsCreated) created++ else updated++
            return id
        }

        suspend fun applyAll() {
            for (event in creates) {
                try {
                    upsert(event, knownEventId = null, countAsCreated = true)
                } catch (e: GcalApiException) {
                    if (e.isAuthError) { authLost = e.message; return }
                    failed++; firstError = firstError ?: "create ${event.payload.summary.take(60)}: ${e.message}"
                } catch (e: Exception) {
                    failed++; firstError = firstError ?: "create ${event.payload.summary.take(60)}: ${e.message}"
                }
            }
            for ((event, item) in updates) {
                try {
                    upsert(event, knownEventId = item.googleEventId, countAsCreated = false)
                } catch (e: GcalApiException) {
                    if (e.isGone) {
                        // Event vanished (deleted in Google): re-adopt or recreate.
                        try {
                            upsert(event, knownEventId = null, countAsCreated = false)
                            warnings += "recreated \"${event.payload.summary.take(60)}\" (Google event was missing)"
                        } catch (e2: Exception) {
                            failed++; firstError = firstError ?: "update ${event.payload.summary.take(60)}: ${e2.message}"
                            if (e2 is GcalApiException && e2.isAuthError) { authLost = e2.message; return }
                        }
                    } else if (e.isAuthError) {
                        authLost = e.message; return
                    } else {
                        failed++; firstError = firstError ?: "update ${event.payload.summary.take(60)}: ${e.message}"
                    }
                } catch (e: Exception) {
                    failed++; firstError = firstError ?: "update ${event.payload.summary.take(60)}: ${e.message}"
                }
            }
            // Deletes: only items this integration recorded, only from a complete scan.
            for (item in deletes) {
                try {
                    apiClient.deleteEvent(token, item.googleEventId)
                    stateStore.removeItem(item.orgId)
                    deleted++
                } catch (e: GcalApiException) {
                    if (e.isGone) {
                        stateStore.removeItem(item.orgId) // already gone on Google: done
                        deleted++
                    } else if (e.isAuthError) {
                        authLost = e.message; return
                    } else {
                        failed++; firstError = firstError ?: "delete ${item.summary.take(60)}: ${e.message}"
                    }
                } catch (e: Exception) {
                    failed++; firstError = firstError ?: "delete ${item.summary.take(60)}: ${e.message}"
                }
            }
        }

        applyAll()

        val outcome = GcalSyncOutcome(
            scanned = scan.filesScanned,
            tracked = scan.events.size,
            created = created,
            updated = updated,
            deleted = deleted,
            unchanged = unchanged,
            failed = failed,
            warnings = warnings
        )
        val lostAuthorization = authLost
        if (lostAuthorization != null) {
            authManager.clearTokenCache()
            record(lostAuthorization, outcome, needsAuthorization = true)
            return GcalSyncResult.NeedsAuthorization(lostAuthorization)
        }
        record(firstError, outcome, needsAuthorization = false)
        return GcalSyncResult.Success(outcome)
    }

    // ---- helpers ----

    private fun payloadJson(payload: GcalEventPayload): String =
        json.encodeToString(GcalEventPayload.serializer(), payload)

    private fun payloadHash(payload: GcalEventPayload): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payloadJson(payload).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private suspend fun recordNeedsAuthorization(message: String) {
        configStore.setLastSyncTime(GcalStateStore.nowEpochMs())
        stateStore.markSynced(
            GcalLastResult(
                at = GcalStateStore.nowStamp(),
                error = message,
                needsAuthorization = true
            )
        )
    }

    private suspend fun recordFailure(message: String) {
        configStore.setLastSyncTime(GcalStateStore.nowEpochMs())
        stateStore.markSynced(
            GcalLastResult(at = GcalStateStore.nowStamp(), error = message)
        )
    }

    private suspend fun record(error: String?, outcome: GcalSyncOutcome, needsAuthorization: Boolean) {
        configStore.setLastSyncTime(GcalStateStore.nowEpochMs())
        stateStore.markSynced(
            GcalLastResult(
                at = GcalStateStore.nowStamp(),
                scanned = outcome.scanned,
                tracked = outcome.tracked,
                created = outcome.created,
                updated = outcome.updated,
                deleted = outcome.deleted,
                unchanged = outcome.unchanged,
                failed = outcome.failed,
                error = error,
                needsAuthorization = needsAuthorization,
                warnings = outcome.warnings
            )
        )
    }
}
