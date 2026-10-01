// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import android.content.Context
import org.pingme.core.service.work.SendAlarm
import org.pingme.core.store.ScheduledSendRepository
import kotlin.time.Clock

/** Counts wake-ups for scheduled sends instead of asking Android's job system. */
class QuietAlarm(
    context: Context,
    scheduled: ScheduledSendRepository,
    clock: Clock,
) : SendAlarm(context, scheduled, clock) {
    var armed = 0

    override suspend fun arm() {
        armed++
    }
}
