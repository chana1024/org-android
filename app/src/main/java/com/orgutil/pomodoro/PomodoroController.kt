package com.orgutil.pomodoro

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.orgutil.MainActivity
import com.orgutil.R
import com.orgutil.widget.AgendaWidgetUpdater
import com.orgutil.data.repository.OrgClockService
import com.orgutil.data.repository.OrgClockTarget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Org-Pomodoro engine. A session is a phase (focus / break) plus an absolute
 * end time persisted through process death; the only wake-up is one
 * AlarmManager alarm at that time (exact when the user granted it, inexact
 * otherwise) — no polling service runs while the timer counts down. The
 * clock-in/out of the selected Org heading goes through [OrgClockService]'s
 * verified surgical write path.
 *
 * Phase semantics mirror org-pomodoro with the user's Doom values: 40-minute
 * focus, 5-minute short break, 20-minute long break every 4th focus; a break
 * never auto-starts the next focus; stopping mid-focus clocks the heading
 * out at the stop moment.
 */
@Singleton
class PomodoroController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: PomodoroStore,
    private val clockService: OrgClockService,
    private val overlay: PomodoroOverlay,
    private val widgetUpdater: AgendaWidgetUpdater
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Single-flight stop: every surface (overlay, banner, notification action)
     * funnels into one [stopInternal] at a time. Without this, repeated Stop
     * taps — the natural reaction to a card that "hasn't reacted yet" — each
     * launch a fresh stop chain and race their surgical writes on the same
     * file (identity checks fail, SAF provider calls serialize), which is
     * exactly the freeze the user saw.
     */
    private val stopMutex = Mutex()

    /**
     * True while a stop is between teardown and the (possibly slow) CLOCK
     * write. [runPendingTransitions] checks it so a late phase-end wake-up
     * can never re-advance a session or re-show the overlay the user just
     * stopped (no ghost card).
     */
    @Volatile
    private var stopInFlight = false

    private val _state = MutableStateFlow<PomodoroSession?>(null)
    val state: StateFlow<PomodoroSession?> = _state.asStateFlow()

    private val _clockErrors = MutableStateFlow<String?>(null)

    /** Latest non-fatal clock-write problem, surfaced by the Agenda notices. */
    val clockErrors: StateFlow<String?> = _clockErrors.asStateFlow()

    // ---- lifecycle -------------------------------------------------------------

    /** Restores state after process death / reboot: reschedule or advance. */
    fun recover() {
        scope.launch { runPendingTransitions() }
    }

    /**
     * Starts a focus session on [target]: clocks the heading in, persists the
     * phase, schedules the alarm and posts the ongoing notification. An
     * already-running session is stopped first (org-pomodoro kill semantics).
     */
    suspend fun start(target: OrgClockTarget, displayTitle: String) = runCatching {
        val previous = store.readSession()
        if (previous != null) stopInternal()
        val settings = store.readSettings()
        val now = System.currentTimeMillis()
        // A clock already open (e.g. from Emacs) is reused untouched.
        clockService.clockIn(target, LocalDateTime.now()).getOrThrow()

        val session = PomodoroSession(
            phase = PomodoroPhase.FOCUS,
            endAtMillis = now + minutesToMillis(settings.focusMinutes),
            clockInAtMillis = now,
            taskTitle = displayTitle,
            taskFileName = target.fileName,
            taskUri = target.uri.toString(),
            taskLevel = target.level,
            taskTodo = target.todo,
            taskTitleText = target.title,
            taskSourceOffset = target.sourceOffset,
            taskTitleOffset = target.titleOffset,
            completedFocusCount = store.readCompletedFocusCount(),
            breakIsLong = false,
            exactAlarm = canScheduleExact()
        )
        store.writeSession(session)
        scheduleAlarm(session)
        _state.value = session
        widgetUpdater.refreshPomodoro()
        postFocusNotification(session)
    }

    /** User stop: cancels everything; mid-focus the clock closes at now. */
    fun stop() {
        scope.launch { stopInternal() }
    }

    /**
     * Teardown-first stop. The clock-out write (FOCUS only) is a multi-second
     * SAF round-trip on a large file — two reads, two full parses, the write,
     * a read-back verify and an index sync — so it must never sit between the
     * Stop tap and the UI reacting. Everything the user sees (state flow,
     * overlay, notifications, alarm) is torn down from in-memory/binder-cheap
     * calls first, giving instant feedback; the Org CLOCK close then completes
     * in the background and still reports failures through the event channel.
     */
    private suspend fun stopInternal() = stopMutex.withLock {
        val session = store.readSession() ?: run {
            cancelAlarm()
            hideOverlayAndNotifications()
            _state.value = null
            widgetUpdater.refreshPomodoro()
            return@withLock
        }
        stopInFlight = true
        _state.value = null
        store.writeSession(null)
        widgetUpdater.refreshPomodoro()
        cancelAlarm()
        hideOverlayAndNotifications()
        try {
            if (session.phase == PomodoroPhase.FOCUS) {
                clockOut(session, LocalDateTime.now())
            }
        } finally {
            stopInFlight = false
        }
    }

    // ---- transitions -----------------------------------------------------------

    /** Fire-and-forget entry for UI surfaces. */
    fun onAlarm() {
        scope.launch { runPendingTransitions() }
    }

    /**
     * Awaitable entry for the broadcast receivers: every expired transition —
     * CLOCK write, alarm reschedule, notification, overlay — completes before
     * returning, so a `goAsync` PendingResult is only finished when the work
     * it guards is done.
     */
    suspend fun onAlarmAwait() = runPendingTransitions()

    /** Awaitable stop for the notification action receiver. */
    suspend fun stopAwait() = stopInternal()

    /**
     * Runs every already-expired transition (or just reschedules when the
     * phase is still running); loop-guarded because one wake-up may cross a
     * focus AND its break.
     */
    private suspend fun runPendingTransitions() {
        // A stop is mid-teardown (or awaiting its CLOCK write): never
        // re-advance or re-show surfaces the user just stopped.
        if (stopInFlight) return
        val stored = store.readSession()
        if (stored == null) {
            cancelAlarm()
            return
        }
        var session: PomodoroSession? = stored
        var guard = 0
        while (session != null && System.currentTimeMillis() >= session.endAtMillis) {
            if (guard++ >= 4) return
            if (stopInFlight) return
            session = advance(session)
        }
        if (stopInFlight) return
        if (session != null) scheduleAlarm(session) else cancelAlarm()
        _state.value = session
        widgetUpdater.refreshPomodoro()
    }

    /**
     * One expired transition: focus → (clock-out at the scheduled end, keeping
     * the Org entry an exact 40 minutes even if the wake-up was late) break;
     * break → idle with a "break over" notification. The rolling focus count
     * (org-pomodoro's history, driving the every-Nth long break) survives the
     * session in [PomodoroStore].
     */
    private suspend fun advance(session: PomodoroSession): PomodoroSession? {
        return when (session.phase) {
            PomodoroPhase.FOCUS -> {
                clockOut(
                    session,
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(session.endAtMillis), ZoneId.systemDefault())
                )
                val settings = store.readSettings()
                val completed = store.readCompletedFocusCount() + 1
                store.writeCompletedFocusCount(completed)
                val isLong = completed % settings.longBreakEvery == 0
                val breakSession = session.copy(
                    phase = PomodoroPhase.BREAK,
                    endAtMillis = System.currentTimeMillis() +
                        minutesToMillis(settings.breakMinutes(isLong)),
                    completedFocusCount = completed,
                    breakIsLong = isLong
                )
                store.writeSession(breakSession)
                showFocusFinished(breakSession)
                breakSession
            }

            PomodoroPhase.BREAK -> {
                store.writeSession(null)
                overlay.hide()
                NotificationManagerCompat.from(context).cancel(NOTIF_BREAK)
                postEventNotification(
                    "Break over",
                    "Ready for the next pomodoro 🍅 — start it from Agenda."
                )
                null
            }
        }
    }

    private suspend fun clockOut(session: PomodoroSession, at: LocalDateTime) {
        val target = OrgClockTarget(
            uri = Uri.parse(session.taskUri),
            fileName = session.taskFileName,
            level = session.taskLevel,
            todo = session.taskTodo,
            title = session.taskTitleText,
            sourceOffset = session.taskSourceOffset,
            titleOffset = session.taskTitleOffset
        )
        clockService.clockOut(target, at)
            .onFailure { error ->
                _clockErrors.value = "Clock-out failed: ${error.message}"
                postEventNotification("Pomodoro clock-out failed", error.message ?: "")
            }
    }

    // ---- wake-ups --------------------------------------------------------------

    private fun scheduleAlarm(session: PomodoroSession) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = alarmPendingIntent()
        val exact = canScheduleExact()
        runCatching {
            if (exact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, session.endAtMillis, pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, session.endAtMillis, pendingIntent
                )
            }
        }
    }

    private fun cancelAlarm() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(alarmPendingIntent())
    }

    private fun alarmPendingIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, PomodoroReceiver::class.java).setAction(PomodoroReceiver.ACTION_PHASE_END),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    // ---- user-facing surfaces --------------------------------------------------

    /**
     * Alarm-time surface: overlay when granted, else (or when the overlay
     * window cannot be added) the heads-up break notification, which is
     * always posted as the persistent handle anyway.
     */
    private suspend fun showFocusFinished(breakSession: PomodoroSession) {
        val minutes = ((breakSession.endAtMillis - System.currentTimeMillis()) / 60_000).toInt() + 1
        if (Settings.canDrawOverlays(context)) {
            val shown = overlay.show(
                context = context,
                session = breakSession,
                onStop = { stop() },
                onDismiss = {
                    // The break keeps running; the notification stays as the handle.
                    scope.launch { overlay.hide() }
                },
                onEnded = { onAlarm() }
            )
            if (!shown) {
                postEventNotification(
                    "Break started (overlay unavailable)",
                    "Could not show the floating break card; the break notification below is the timer's handle."
                )
            }
        }
        postBreakNotification(breakSession, minutes)
    }

    private suspend fun hideOverlayAndNotifications() {
        overlay.hide()
        NotificationManagerCompat.from(context).apply {
            cancel(NOTIF_FOCUS)
            cancel(NOTIF_BREAK)
        }
    }

    private fun contentIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun actionPendingIntent(action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, PomodoroReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS, "Pomodoro status", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Ongoing pomodoro focus timer" }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EVENTS, "Pomodoro events", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Focus finished / break over" }
        )
    }

    private fun builder(channel: String): NotificationCompat.Builder {
        ensureChannels()
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_pomodoro_24)
            .setContentIntent(contentIntent())
            .setCategory(NotificationCompat.CATEGORY_ALARM)
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun postFocusNotification(session: PomodoroSession) {
        if (!canPost()) return
        val endsAt = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
            .format(LocalDateTime.ofInstant(Instant.ofEpochMilli(session.endAtMillis), ZoneId.systemDefault()))
        val settings = store.readSettings()
        val notification = builder(CHANNEL_STATUS)
            .setContentTitle("🍅 Focus: ${session.taskTitle}")
            .setContentText("Pomodoro ends at $endsAt (${settings.focusMinutes} min)")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(
                0, "Stop",
                actionPendingIntent(PomodoroReceiver.ACTION_STOP)
            )
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_FOCUS, notification) }
    }

    private fun postBreakNotification(session: PomodoroSession, minutes: Int) {
        if (!canPost()) return
        val notification = builder(CHANNEL_EVENTS)
            .setContentTitle("☕ ${if (session.breakIsLong) "Long" else "Short"} break — $minutes min")
            .setContentText("Focus done: ${session.taskTitle}. Break runs until it ends; stop any time.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", actionPendingIntent(PomodoroReceiver.ACTION_STOP))
            .addAction(0, "Dismiss", actionPendingIntent(PomodoroReceiver.ACTION_DISMISS))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_BREAK, notification) }
    }

    private fun postEventNotification(title: String, text: String) {
        if (!canPost()) return
        val notification = builder(CHANNEL_EVENTS)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_EVENT, notification) }
    }

    // ---- permission probes (for the Agenda start dialog) -----------------------

    fun pomodoroSettings(): PomodoroSettings = store.readSettings()

    fun savePomodoroSettings(settings: PomodoroSettings) = store.writeSettings(settings)

    fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        } else {
            true
        }

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    fun notificationsGranted(): Boolean = canPost()

    private fun minutesToMillis(minutes: Int): Long = minutes * 60_000L

    private companion object {
        const val REQUEST_CODE = 4001
        const val CHANNEL_STATUS = "pomodoro_status"
        const val CHANNEL_EVENTS = "pomodoro_events"
        const val NOTIF_FOCUS = 2001
        const val NOTIF_BREAK = 2002
        const val NOTIF_EVENT = 2003
    }
}
