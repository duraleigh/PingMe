// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service.work

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.pingme.core.store.ScheduledSendRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock

/**
 * Wakes the phone to send the next scheduled message (UI_DESIGN.md 10.13). Until P4.2 adds
 * the exact alarm, a delayed background job does it, which Android may run a few minutes
 * late; the job waits for a network connection, so a message due while offline goes as soon
 * as the phone is back online.
 */
@Singleton
open class SendAlarm
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val scheduled: ScheduledSendRepository,
        private val clock: Clock,
    ) {
        /** Sets the wake-up for whichever scheduled message is due first; does nothing when none are left. */
        open suspend fun arm() {
            val next = scheduled.all().first().minByOrNull { it.sendAt } ?: return
            Work.sendScheduledAfter(context, next.sendAt - clock.now())
        }
    }
