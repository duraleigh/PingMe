// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.pingme.core.store.ScheduledSendRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock

/**
 * Wakes the phone to send the next scheduled message (UI_DESIGN.md 10.13, BUILD_PLAN.md
 * P4.2): an exact alarm that fires on the minute even while the phone sleeps, when the user
 * has allowed "Alarms and reminders"; and, always, a delayed background job as the fallback,
 * which waits for a network connection, so a message due while offline goes as soon as the
 * phone is back online. Without the permission the job alone may run a few minutes late.
 */
@Singleton
open class SendAlarm
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val scheduled: ScheduledSendRepository,
        private val clock: Clock,
    ) {
        private val manager: AlarmManager? get() = context.getSystemService(AlarmManager::class.java)

        /** Whether exact alarms are allowed (Android 12 and newer ask the user; older always allow). */
        fun canScheduleExactly(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager?.canScheduleExactAlarms() == true

        /** Sets the wake-up for whichever scheduled message is due first; clears it when none are left. */
        open suspend fun arm() {
            val next = scheduled.all().first().minByOrNull { it.sendAt }
            val alarm = alarmIntent()
            if (next == null) {
                manager?.cancel(alarm)
                return
            }
            if (canScheduleExactly()) {
                manager?.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.sendAt.toEpochMilliseconds(), alarm)
            } else {
                manager?.cancel(alarm)
            }
            Work.sendScheduledAfter(context, next.sendAt - clock.now())
        }

        private fun alarmIntent(): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, ScheduledSendReceiver::class.java).setAction(ScheduledSendReceiver.ACTION_DUE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
