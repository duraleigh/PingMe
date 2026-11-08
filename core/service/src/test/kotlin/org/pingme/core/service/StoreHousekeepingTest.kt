// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pingme.core.connector.chat
import org.pingme.core.connector.message
import org.pingme.core.model.MessageStatus
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class StoreHousekeepingTest : ServiceTest() {
    /** A message left on "sending" by a closed app says so, and can be tried again (owner, 2026-11-01). */
    @Test
    fun aMessageStuckOnSendingBecomesNotSent() =
        runTest {
            accounts.upsert(account())
            applier.applyChats(listOf(chatSnapshot()))
            val stuck = messageSnapshot("stuck", sentAt = now - 5.minutes).message
            val fresh = messageSnapshot("fresh", sentAt = now - 10.seconds).message
            messages.upsert(stuck.copy(isOutgoing = true, status = MessageStatus.Sending))
            messages.upsert(fresh.copy(isOutgoing = true, status = MessageStatus.Sending))
            StoreHousekeeping(
                messages,
                clock,
                org.pingme.core.store
                    .MergeRepository(db),
            ).run()
            assertTrue(messages.get(accountId.message("stuck"))?.status is MessageStatus.Failed)
            assertEquals(MessageStatus.Sending, messages.get(accountId.message("fresh"))?.status)
            assertEquals(accountId.chat("c1"), messages.get(accountId.message("stuck"))?.chatId)
        }
}
