package com.orgutil.pomodoro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Pomodoro wake-up surface: the single phase-end alarm and the
 * notification action buttons. Non-exported (system-broadcast registration
 * only through explicit PendingIntents), so no other app can forge ACTION_STOP
 * to kill a session. Every action awaits the controller's full transition —
 * CLOCK write, alarm reschedule, notification, overlay — before the goAsync
 * PendingResult is finished; truth is always re-derived from the persisted
 * absolute end time, so a late or duplicated delivery cannot double-advance
 * a phase.
 */
@AndroidEntryPoint
class PomodoroReceiver : BroadcastReceiver() {

    @Inject
    lateinit var controller: PomodoroController

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    ACTION_PHASE_END -> controller.onAlarmAwait()

                    ACTION_STOP -> controller.stopAwait()

                    ACTION_DISMISS -> {
                        // The break itself keeps running on its alarm; only
                        // the notification handle goes away.
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_PHASE_END = "com.orgutil.pomodoro.PHASE_END"
        const val ACTION_STOP = "com.orgutil.pomodoro.STOP"
        const val ACTION_DISMISS = "com.orgutil.pomodoro.DISMISS"
    }
}
