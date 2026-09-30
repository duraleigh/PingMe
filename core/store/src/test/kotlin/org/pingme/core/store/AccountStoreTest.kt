// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pingme.core.model.AccountId
import org.pingme.core.model.ChatId
import org.pingme.core.model.ConnectionState
import org.pingme.core.model.MessageId
import org.pingme.core.model.NetworkId
import org.pingme.core.model.PersonId

class AccountStoreTest : StoreTest() {
    @Test
    fun upsertAndReadBack() =
        runTest {
            val a = account("a", NetworkId.WHATSAPP).copy(state = ConnectionState.ActionNeeded("Re-link", "wa://"))
            accounts.upsert(a)
            assertEquals(a, accounts.get(AccountId("a")))
            assertEquals(listOf(a), accounts.accounts().first())
            accounts.upsert(a.copy(displayName = "WhatsApp (work)"))
            assertEquals("WhatsApp (work)", accounts.account(AccountId("a")).first()?.displayName)
        }

    @Test
    fun updateStateChangesOnlyTheState() =
        runTest {
            accounts.upsert(account("a"))
            val reconnecting = ConnectionState.Reconnecting(attempt = 2, nextAt = now)
            accounts.updateState(AccountId("a"), reconnecting)
            assertEquals(account("a").copy(state = reconnecting), accounts.get(AccountId("a")))
        }

    @Test
    fun deletingAnAccountRemovesItsChatsMessagesAndPeople() =
        runTest {
            accounts.upsert(account("a"))
            chats.upsert(chat("c", "a"))
            messages.upsert(message("m", "c"))
            contacts.upsert(person("p", "a", "Sam"))
            accounts.delete(AccountId("a"))
            assertNull(accounts.get(AccountId("a")))
            assertNull(chats.get(ChatId("c")))
            assertNull(messages.get(MessageId("m")))
            assertNull(contacts.person(PersonId("p")))
        }

    @Test
    fun savingAnAccountOrChatAgainKeepsWhatBelongsToIt() =
        runTest {
            accounts.upsert(account("a"))
            chats.upsert(chat("c", "a"))
            messages.upsert(message("m", "c"))
            contacts.upsert(person("p", "a", "Sam"))
            accounts.upsert(account("a").copy(displayName = "renamed"))
            chats.upsert(chat("c", "a").copy(title = "renamed"))
            assertEquals("renamed", chats.get(ChatId("c"))?.title)
            assertEquals("hello", messages.get(MessageId("m"))?.body)
            assertEquals("Sam", contacts.person(PersonId("p"))?.displayName)
        }
}
