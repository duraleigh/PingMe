// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service.work

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.pingme.core.service.ApplicationScope
import javax.inject.Inject

/**
 * The exact alarm fired: send what is due (BUILD_PLAN.md P4.2). Also re-arms after a
 * restart (alarms do not survive one) and when the user grants "Alarms and reminders".
 */
@AndroidEntryPoint
class ScheduledSendReceiver : BroadcastReceiver() {
    @Inject lateinit var alarm: SendAlarm

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            ACTION_DUE -> {
                Work.sendScheduled(context)
            }

            Intent.ACTION_BOOT_COMPLETED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> {
                val pending = goAsync()
                scope.launch {
                    try {
                        alarm.arm()
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_DUE = "org.pingme.action.SCHEDULED_SEND_DUE"
    }
}
