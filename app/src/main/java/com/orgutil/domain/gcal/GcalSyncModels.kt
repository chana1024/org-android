package com.orgutil.domain.gcal

import kotlinx.coroutines.flow.Flow

/**
 * One-way GTD -> Google Calendar sync models, mirroring the Doom
 * implementation (my/org-gtd-sync-to-google-calendar in
 * ~/.config/doom/lisp/org/14-org-gcal-sync.el):
 *
 * - Scans exactly the Agenda GTD source files under gtd/ (inbox.org, gtd.org,
 *   areas.org, projects.org, someday.org, tickler.org, routines.org).
 * - Syncs entries with SCHEDULED or DEADLINE (SCHEDULED preferred), excluding
 *   DONE / CANCELLED / DROPPED.
 * - Calendar timestamps are independent from the Agenda planning editor
 *   default: date-only entries start 10:00 Asia/Shanghai and last 30 minutes;
 *   explicit Org times are preserved, explicit end times respected, and a
 *   timed start without end lasts 30 minutes. Repeaters do not become Google
 *   recurrence rules (the reference sync does not emit them either).
 * - Deletion only ever targets events this integration previously recorded,
 *   and never runs when the source scan is incomplete or failed.
 */
sealed interface GcalSyncStatus {
    data object Idle : GcalSyncStatus
    data object Enqueued : GcalSyncStatus
    data class Running(val step: String) : GcalSyncStatus
    data class Succeeded(val detail: String, val at: Long) : GcalSyncStatus
    /** Authorization (consent) is required; the Sync screen must relaunch it. */
    data class NeedsAuthorization(val message: String, val at: Long) : GcalSyncStatus
    data class Failed(val message: String, val at: Long) : GcalSyncStatus
}

sealed interface GcalSyncRequestResult {
    data object Enqueued : GcalSyncRequestResult
    data object NotConfigured : GcalSyncRequestResult
    data class Failed(val message: String) : GcalSyncRequestResult
}

/** Counters for one finished sync run (created/updated/deleted may include per-run failures in [failed]). */
data class GcalSyncOutcome(
    val scanned: Int,
    val tracked: Int,
    val created: Int,
    val updated: Int,
    val deleted: Int,
    val unchanged: Int,
    val failed: Int,
    val warnings: List<String> = emptyList()
) {
    val detail: String
        get() = "$tracked tracked · $created created, $updated updated, $deleted deleted" +
            (if (failed > 0) ", $failed failed" else "")
}

/** Scheduling surface for the Google Calendar sync worker. */
interface GcalScheduler {
    /** Enqueues a one-shot sync now (network-constrained, unique work). */
    fun requestSync(): GcalSyncRequestResult

    /** (Re)arms the 30-minute periodic sync when enabled and authorized; a no-op otherwise. */
    fun ensurePeriodicSync(): GcalSyncRequestResult

    /** Stops the periodic sync (auto-sync toggle off / disconnect). */
    fun cancelPeriodicSync()

    fun observeSync(): Flow<GcalSyncStatus>
}
