// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pingme.core.model.AccountId
import org.pingme.core.model.ChatId

/**
 * Removing an account once deleted the merged chats filed under it, leaving members on other
 * accounts pointing at nothing and hidden from the inbox (owner, 2026-11-07: Merle, Devin and
 * Melinda's Instagram chats vanished after the Google Messages account went).
 */
class MergeStoreTest : StoreTest() {
    private val merges by lazy { MergeRepository(db) }

    private suspend fun mergedAcrossTwoAccounts() {
        accounts.upsert(account("gm"))
        accounts.upsert(account("ig"))
        chats.upsert(chat("merged/1", "gm").copy(defaultSendAccount = AccountId("gm")))
        chats.upsert(chat("gm-merle", "gm"))
        chats.upsert(chat("ig-merle", "ig").copy(title = "Merle"))
        merges.setMergedInto(ChatId("gm-merle"), ChatId("merged/1"))
        merges.setMergedInto(ChatId("ig-merle"), ChatId("merged/1"))
    }

    @Test
    fun removingAnAccountKeepsAMergedChatWhoseOtherMembersRemain() =
        runTest {
            mergedAcrossTwoAccounts()
            merges.beforeAccountRemoved(AccountId("gm"))
            accounts.delete(AccountId("gm"))

            val parent = chats.get(ChatId("merged/1"))
            assertEquals(AccountId("ig"), parent?.accountId)
            assertEquals(AccountId("ig"), parent?.defaultSendAccount)
            assertEquals(ChatId("merged/1"), chats.get(ChatId("ig-merle"))?.mergedInto)
            assertEquals(0, merges.releaseStranded())
        }

    @Test
    fun aChatWhoseMergedChatIsGoneIsFreedAndNamedFirst() =
        runTest {
            mergedAcrossTwoAccounts()
            accounts.delete(AccountId("gm")) // the old way: the merged chat goes with the account

            val named = mutableListOf<String>()
            assertEquals(1, merges.releaseStranded { named += it })
            assertNull(chats.get(ChatId("ig-merle"))?.mergedInto)
            assertEquals(1, named.size)
            assertTrue(named.single(), named.single().startsWith("'Merle' ig-merle"))
            assertEquals(0, merges.releaseStranded())
        }
}
