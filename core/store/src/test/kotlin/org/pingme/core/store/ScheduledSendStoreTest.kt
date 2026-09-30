// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.pingme.core.model.AccountId
import org.pingme.core.model.ChatId
import org.pingme.core.model.MessageId
import org.pingme.core.model.ScheduledSend
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class ScheduledSendStoreTest : StoreTest() {
    private fun send(
        id: String,
        chat: String,
        at: kotlin.time.Instant,
    ) = ScheduledSend(MessageId(id), at, AccountId("a"), ChatId(chat), """{"text":"hi"}""", attempts = 0)

    @Test
    fun dueIncludesLateAndOnTimeButNotFutureSends() =
        runTest {
            val late = send("late", "c1", now - 1.hours)
            val onTime = send("on-time", "c1", now)
            val future = send("future", "c2", now + 1.minutes)
            listOf(future, onTime, late).forEach { messages.upsertScheduled(it) }
            assertEquals(listOf(late, onTime), messages.dueScheduled(now))
            assertEquals(listOf(late, onTime, future), messages.scheduled().first())
            assertEquals(listOf(future), messages.scheduledIn(ChatId("c2")).first())
        }

    @Test
    fun attemptsCountUpAndSentOnesAreRemoved() =
        runTest {
            messages.upsertScheduled(send("m", "c", now))
            messages.recordScheduledAttempt(MessageId("m"))
            messages.recordScheduledAttempt(MessageId("m"))
            assertEquals(2, messages.dueScheduled(now).single().attempts)
            messages.deleteScheduled(MessageId("m"))
            assertEquals(emptyList<ScheduledSend>(), messages.scheduled().first())
        }
}
