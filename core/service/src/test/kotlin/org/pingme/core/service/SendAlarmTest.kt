// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pingme.core.connector.OutgoingMessage
import org.pingme.core.connector.chat
import org.pingme.core.connector.message
import org.pingme.core.model.ScheduledSend
import org.pingme.core.service.work.SendAlarm
import org.pingme.core.store.ScheduledSendRepository
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import kotlin.time.Duration.Companion.minutes

/** The exact alarm follows the next scheduled message (BUILD_PLAN.md P4.2). */
class SendAlarmTest : ServiceTest() {
    private val scheduled by lazy { ScheduledSendRepository(db) }
    private val alarm by lazy { SendAlarm(context, scheduled, clock) }
    private val manager
        get() = ApplicationProvider.getApplicationContext<Context>().getSystemService(AlarmManager::class.java)

    @org.junit.Before
    fun startWork() {
        // The alarm also arms the fallback job, which needs a WorkManager to talk to.
        androidx.work.testing.WorkManagerTestInitHelper
            .initializeTestWorkManager(context)
    }

    private fun send(
        id: String,
        at: kotlin.time.Instant,
    ) = ScheduledSend(
        accountId.message(id),
        at,
        accountId,
        accountId.chat("c1"),
        Json.encodeToString(
            OutgoingMessage.serializer(),
            OutgoingMessage(accountId.message(id), "x", emptyList(), null, null, false),
        ),
        0,
    )

    @Test
    fun theAlarmIsSetForTheEarliestMessageAndClearedWhenNoneRemain() =
        runBlocking {
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
            scheduled.upsert(send("later", now + 30.minutes))
            scheduled.upsert(send("soon", now + 5.minutes))
            alarm.arm()
            val set = requireNotNull(shadowOf(manager).peekNextScheduledAlarm())
            assertEquals((now + 5.minutes).toEpochMilliseconds(), set.triggerAtTime)
            assertEquals(AlarmManager.RTC_WAKEUP, set.type)
            scheduled.delete(accountId.message("soon"))
            scheduled.delete(accountId.message("later"))
            alarm.arm()
            assertNull(shadowOf(manager).peekNextScheduledAlarm())
        }

    @Test
    fun withoutThePermissionOnlyTheBackgroundJobIsUsed() =
        runBlocking {
            ShadowAlarmManager.setCanScheduleExactAlarms(false)
            scheduled.upsert(send("soon", now + 5.minutes))
            alarm.arm()
            assertNull(shadowOf(manager).peekNextScheduledAlarm())
            assertEquals(false, alarm.canScheduleExactly())
        }
}
