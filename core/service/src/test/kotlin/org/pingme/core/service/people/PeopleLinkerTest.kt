// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service.people

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pingme.core.connector.AddressBook
import org.pingme.core.connector.AddressBookEntry
import org.pingme.core.connector.ConnectorEvent
import org.pingme.core.connector.chat
import org.pingme.core.connector.person
import org.pingme.core.model.ContactId
import org.pingme.core.service.EventApplier
import org.pingme.core.service.PeopleApplier
import org.pingme.core.service.ServiceTest
import org.pingme.core.service.Tapbacks

/** Matching people to the phone's contacts (UI_DESIGN.md 10.18, BUILD_PLAN.md P7.1). */
class PeopleLinkerTest : ServiceTest() {
    private class FakeBook(
        var entries: List<AddressBookEntry>,
    ) : AddressBook {
        override suspend fun entries() = entries
    }

    private val book =
        FakeBook(listOf(AddressBookEntry("Sam Ortiz", listOf("+15555550123"), "lookup-42", "content://photo/42")))
    private val linker by lazy { PeopleLinker(book, contacts, chats) }
    private val withBook by lazy {
        EventApplier(
            accounts,
            chats,
            messages,
            contacts,
            typing,
            reactionFeed,
            Tapbacks(messages),
            PeopleApplier(chats, contacts, linker, ProfilePhotos(context, contacts, scope)),
        )
    }

    private fun samByNumber() = sam().copy(displayName = "+15555550123", phoneNumber = "+15555550123")

    @Test
    fun aPersonWithAContactsNumberCarriesTheContactsNameAndPhoto() =
        runTest {
            accounts.upsert(account())
            withBook.apply(ConnectorEvent.PeopleUpdated(accountId, listOf(samByNumber())))
            val stored = contacts.person(sam().id)!!
            assertEquals(ContactId("lookup-42"), stored.contactId)
            assertEquals("Sam Ortiz", stored.contactName)
            assertEquals("content://photo/42", stored.contactPhoto)
            assertEquals("the contact's name is the one shown", "Sam Ortiz", stored.name)
            assertEquals("+15555550123", stored.displayName)
        }

    @Test
    fun aChatTitledByANumberTakesTheContactsName() =
        runTest {
            accounts.upsert(account())
            val snapshot = chatSnapshot("c1", title = "+15555550123").copy(participants = listOf(samByNumber()))
            withBook.apply(ConnectorEvent.ChatUpdated(accountId, snapshot))
            assertEquals("Sam Ortiz", chats.get(accountId.chat("c1"))?.title)
        }

    @Test
    fun aNumberWrittenWithoutItsCountryStillMatches() =
        runTest {
            accounts.upsert(account())
            book.entries = listOf(AddressBookEntry("Sam Ortiz", listOf("5555550123"), "lookup-42", null))
            withBook.apply(ConnectorEvent.PeopleUpdated(accountId, listOf(samByNumber())))
            assertEquals("Sam Ortiz", contacts.person(sam().id)?.contactName)
        }

    @Test
    fun matchingAgainFollowsTheContactsAndRenamesStoredChats() =
        runTest {
            accounts.upsert(account())
            // Stored before contacts were allowed: no link, a number for a title.
            applier.apply(ConnectorEvent.ChatUpdated(accountId, chatSnapshot("c1", title = "+15555550123")))
            applier.apply(ConnectorEvent.PeopleUpdated(accountId, listOf(samByNumber())))
            assertNull(contacts.person(sam().id)?.contactId)
            linker.relinkAll()
            assertEquals("Sam Ortiz", contacts.person(sam().id)?.contactName)
            assertEquals("Sam Ortiz", chats.get(accountId.chat("c1"))?.title)
            // The contact is gone from the phone: the link goes, the name stays on the chat.
            book.entries = listOf(AddressBookEntry("Someone Else", listOf("+15555559999"), "lookup-9", null))
            linker.relinkAll()
            assertNull(contacts.person(sam().id)?.contactId)
            assertNull(contacts.person(sam().id)?.contactName)
            assertEquals("Sam Ortiz", chats.get(accountId.chat("c1"))?.title)
        }

    @Test
    fun withoutAnAddressBookExistingLinksAreLeftAlone() =
        runTest {
            accounts.upsert(account())
            contacts.upsert(samByNumber().copy(contactId = ContactId("lookup-42"), contactName = "Sam Ortiz"))
            book.entries = emptyList()
            linker.relinkAll()
            assertEquals("Sam Ortiz", contacts.person(sam().id)?.contactName)
            // A network snapshot without the link keeps it too.
            applier.apply(ConnectorEvent.PeopleUpdated(accountId, listOf(samByNumber())))
            assertEquals(ContactId("lookup-42"), contacts.person(sam().id)?.contactId)
            assertEquals(accountId.person("sam"), contacts.person(sam().id)?.id)
        }
}
