// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.connector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.pingme.core.model.AccountId

class ScopedIdsTest {
    private val work = AccountId("acc-work")
    private val home = AccountId("acc-home")

    @Test
    fun idsSayWhichAccountTheyBelongToAndKeepTheNetworksId() {
        val chat = work.chat("thread/42:x")
        assertEquals(work, chat.accountId)
        assertEquals("thread/42:x", chat.remoteId)
        assertEquals(home, home.message("m1").accountId)
        assertEquals("a1", work.attachment("a1").remoteId)
        assertEquals(work, work.person("+1555").accountId)
        assertEquals("community", home.space("community").remoteId)
    }

    @Test
    fun theSameNetworkIdOnTwoAccountsNeverCollides() {
        assertNotEquals(work.chat("42"), home.chat("42"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun accountIdsWithTheSeparatorAreRejected() {
        AccountId("bad/id").chat("1")
    }
}
