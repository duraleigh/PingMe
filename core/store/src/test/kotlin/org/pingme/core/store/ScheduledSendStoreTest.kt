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
    private val scheduled by lazy { ScheduledSendRepository(db) }

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
            listOf(future, onTime, late).forEach { scheduled.upsert(it) }
            assertEquals(listOf(late, onTime), scheduled.due(now))
            assertEquals(listOf(late, onTime, future), scheduled.all().first())
            assertEquals(listOf(future), scheduled.inChat(ChatId("c2")).first())
        }

    @Test
    fun attemptsCountUpAndSentOnesAreRemoved() =
        runTest {
            scheduled.upsert(send("m", "c", now))
            scheduled.recordAttempt(MessageId("m"))
            scheduled.recordAttempt(MessageId("m"))
            assertEquals(2, scheduled.due(now).single().attempts)
            scheduled.delete(MessageId("m"))
            assertEquals(emptyList<ScheduledSend>(), scheduled.all().first())
        }
}
