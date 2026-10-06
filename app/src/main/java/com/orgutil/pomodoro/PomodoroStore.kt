package com.orgutil.pomodoro

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** One running Pomodoro phase. */
enum class PomodoroPhase { FOCUS, BREAK }

/**
 * User-configurable durations (minutes). Defaults mirror the user's Doom
 * `org-pomodoro` setup: 40-minute focus, and org-pomodoro's upstream break
 * semantics — 5-minute short break, 20-minute long break every 4 focus
 * sessions, no auto-chained focus after a break.
 */
data class PomodoroSettings(
    val focusMinutes: Int = 40,
    val shortBreakMinutes: Int = 5,
    val longBreakMinutes: Int = 20,
    val longBreakEvery: Int = 4
) {
    fun breakMinutes(isLong: Boolean) =
        if (isLong) longBreakMinutes else shortBreakMinutes
}

/**
 * The persisted session: everything needed to drive the timer after process
 * death or reboot. Correctness never depends on when a wake-up arrives — the
 * phase is judged against the absolute [endAtMillis].
 */
data class PomodoroSession(
    val phase: PomodoroPhase,
    val endAtMillis: Long,
    val clockInAtMillis: Long,
    val taskTitle: String,
    val taskFileName: String?,
    val taskUri: String,
    val taskLevel: Int,
    val taskTodo: String?,
    val taskTitleText: String,
    val taskSourceOffset: Int,
    val taskTitleOffset: Int,
    /** Focus sessions completed before the current one (long-break counter). */
    val completedFocusCount: Int,
    val breakIsLong: Boolean,
    /** Whether the alarm was scheduled as exact (for reporting only). */
    val exactAlarm: Boolean
)

/**
 * SharedPreferences-backed persistence for Pomodoro settings and the active
 * session. Plain key/value state survives process death and reboot without
 * any database migration.
 */
@Singleton
class PomodoroStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val settingsPrefs: SharedPreferences =
        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)

    private val sessionPrefs: SharedPreferences =
        context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)

    fun readSettings(): PomodoroSettings = PomodoroSettings(
        focusMinutes = settingsPrefs.getInt(KEY_FOCUS, DEFAULT.focusMinutes),
        shortBreakMinutes = settingsPrefs.getInt(KEY_SHORT, DEFAULT.shortBreakMinutes),
        longBreakMinutes = settingsPrefs.getInt(KEY_LONG, DEFAULT.longBreakMinutes),
        longBreakEvery = settingsPrefs.getInt(KEY_EVERY, DEFAULT.longBreakEvery)
    )

    /**
     * org-pomodoro's rolling history: completed focus sessions across the
     * day, driving the every-Nth long break. Lives apart from the session so
     * it survives a session ending.
     */
    fun readCompletedFocusCount(): Int =
        settingsPrefs.getInt(KEY_COMPLETED_FOCUS_ROLLING, 0)

    fun writeCompletedFocusCount(count: Int) {
        settingsPrefs.edit().putInt(KEY_COMPLETED_FOCUS_ROLLING, count).apply()
    }

    fun writeSettings(settings: PomodoroSettings) {
        settingsPrefs.edit()
            .putInt(KEY_FOCUS, settings.focusMinutes.coerceIn(1, 240))
            .putInt(KEY_SHORT, settings.shortBreakMinutes.coerceIn(1, 120))
            .putInt(KEY_LONG, settings.longBreakMinutes.coerceIn(1, 180))
            .putInt(KEY_EVERY, settings.longBreakEvery.coerceIn(1, 12))
            .apply()
    }

    fun readSession(): PomodoroSession? {
        if (!sessionPrefs.contains(KEY_PHASE)) return null
        return runCatching {
            PomodoroSession(
                phase = PomodoroPhase.valueOf(sessionPrefs.getString(KEY_PHASE, null) ?: return null),
                endAtMillis = sessionPrefs.getLong(KEY_END_AT, 0L),
                clockInAtMillis = sessionPrefs.getLong(KEY_CLOCK_IN_AT, 0L),
                taskTitle = sessionPrefs.getString(KEY_TASK_TITLE, "") ?: "",
                taskFileName = sessionPrefs.getString(KEY_TASK_FILE, null),
                taskUri = sessionPrefs.getString(KEY_TASK_URI, null) ?: return null,
                taskLevel = sessionPrefs.getInt(KEY_TASK_LEVEL, 0),
                taskTodo = sessionPrefs.getString(KEY_TASK_TODO, null),
                taskTitleText = sessionPrefs.getString(KEY_TASK_TITLE_TEXT, "") ?: "",
                taskSourceOffset = sessionPrefs.getInt(KEY_TASK_SOURCE_OFFSET, -1),
                taskTitleOffset = sessionPrefs.getInt(KEY_TASK_TITLE_OFFSET, -1),
                completedFocusCount = sessionPrefs.getInt(KEY_COMPLETED_FOCUS, 0),
                breakIsLong = sessionPrefs.getBoolean(KEY_BREAK_LONG, false),
                exactAlarm = sessionPrefs.getBoolean(KEY_EXACT, false)
            )
        }.getOrNull()
    }

    fun writeSession(session: PomodoroSession?) {
        if (session == null) {
            sessionPrefs.edit().clear().apply()
            return
        }
        sessionPrefs.edit()
            .putString(KEY_PHASE, session.phase.name)
            .putLong(KEY_END_AT, session.endAtMillis)
            .putLong(KEY_CLOCK_IN_AT, session.clockInAtMillis)
            .putString(KEY_TASK_TITLE, session.taskTitle)
            .putString(KEY_TASK_FILE, session.taskFileName)
            .putString(KEY_TASK_URI, session.taskUri)
            .putInt(KEY_TASK_LEVEL, session.taskLevel)
            .putString(KEY_TASK_TODO, session.taskTodo)
            .putString(KEY_TASK_TITLE_TEXT, session.taskTitleText)
            .putInt(KEY_TASK_SOURCE_OFFSET, session.taskSourceOffset)
            .putInt(KEY_TASK_TITLE_OFFSET, session.taskTitleOffset)
            .putInt(KEY_COMPLETED_FOCUS, session.completedFocusCount)
            .putBoolean(KEY_BREAK_LONG, session.breakIsLong)
            .putBoolean(KEY_EXACT, session.exactAlarm)
            .apply()
    }

    private companion object {
        const val SETTINGS_PREFS = "pomodoro_settings"
        const val SESSION_PREFS = "pomodoro_session"
        val DEFAULT = PomodoroSettings()

        const val KEY_FOCUS = "focus_minutes"
        const val KEY_SHORT = "short_break_minutes"
        const val KEY_LONG = "long_break_minutes"
        const val KEY_EVERY = "long_break_every"
        const val KEY_COMPLETED_FOCUS_ROLLING = "completed_focus_rolling"

        const val KEY_PHASE = "phase"
        const val KEY_END_AT = "end_at"
        const val KEY_CLOCK_IN_AT = "clock_in_at"
        const val KEY_TASK_TITLE = "task_title"
        const val KEY_TASK_FILE = "task_file"
        const val KEY_TASK_URI = "task_uri"
        const val KEY_TASK_LEVEL = "task_level"
        const val KEY_TASK_TODO = "task_todo"
        const val KEY_TASK_TITLE_TEXT = "task_title_text"
        const val KEY_TASK_SOURCE_OFFSET = "task_source_offset"
        const val KEY_TASK_TITLE_OFFSET = "task_title_offset"
        const val KEY_COMPLETED_FOCUS = "completed_focus"
        const val KEY_BREAK_LONG = "break_long"
        const val KEY_EXACT = "exact_alarm"
    }
}
