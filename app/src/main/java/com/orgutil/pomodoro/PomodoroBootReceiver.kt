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
 * Boot / package-replaced restore for the Pomodoro timer: reschedules the
 * pending alarm or advances already-expired phases (closing the Org clock at
 * the scheduled end time). Exported is required for the protected
 * BOOT_COMPLETED system broadcast — only these two system actions are
 * handled here; all app-internal actions live in the non-exported
 * [PomodoroReceiver]. Like it, this receiver awaits the controller's work
 * before finishing its goAsync PendingResult.
 */
@AndroidEntryPoint
class PomodoroBootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var controller: PomodoroController

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED -> controller.onAlarmAwait()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
