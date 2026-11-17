// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pingme.core.model.ContactId
import org.pingme.core.model.PersonId

/** Your number stored as another person is made yours (owner, 2026-11-09: 73 chats showed your photo). */
class SelfEntriesTest : StoreTest() {
    @Test
    fun anEntryWithYourNumberUnderAnotherNameBecomesYouWithNoContact() =
        runTest {
            accounts.upsert(account("gm"))
            accounts.upsert(account("wa"))
            contacts.upsert(person("wa/me", "wa", "You", "+15555550100"))
            contacts.upsert(
                person("gm/2", "gm", "Some Contact", "+15555550100").copy(
                    contactId = ContactId("card"),
                    contactName = "Some Contact",
                    contactPhoto = "content://photo/card",
                ),
            )
            contacts.upsert(person("gm/sam", "gm", "Sam", "+15555550123"))

            val named = mutableListOf<String>()
            assertEquals(1, SelfEntries(db).repair { named += it })

            val fixed = contacts.person(PersonId("gm/2"))!!
            assertEquals("You", fixed.displayName)
            assertNull(fixed.contactId)
            assertNull(fixed.contactPhoto)
            assertEquals("Sam", contacts.person(PersonId("gm/sam"))?.displayName)
            assertEquals(1, named.size)
            assertEquals(0, SelfEntries(db).repair())
        }

    @Test
    fun anEntryInManyDifferentPeoplesChatsIsYouAndStaysYou() =
        runTest {
            accounts.upsert(account("gm"))
            contacts.upsert(
                person("gm/2", "gm", "Some Contact", "+15555550199").copy(contactPhoto = "content://photo/card"),
            )
            contacts.upsert(person("gm/sam", "gm", "Sam", "+15555550123"))
            repeat(5) { i ->
                chats.upsert(chat("gm/c$i", "gm").copy(title = "Person $i", participants = listOf(PersonId("gm/2"))))
            }
            chats.upsert(chat("gm/s1", "gm").copy(title = "Sam", participants = listOf(PersonId("gm/sam"))))
            chats.upsert(chat("gm/s2", "gm").copy(title = "Sam O", participants = listOf(PersonId("gm/sam"))))

            assertEquals(1, SelfEntries(db).repair())
            assertEquals("You", contacts.person(PersonId("gm/2"))?.displayName)
            assertEquals("Sam", contacts.person(PersonId("gm/sam"))?.displayName)

            // A later message naming you by your contact does not undo it.
            contacts.upsert(
                person("gm/2", "gm", "Some Contact", "+15555550199").copy(contactPhoto = "content://photo/card"),
            )
            val stored = contacts.person(PersonId("gm/2"))!!
            assertEquals("You", stored.displayName)
            assertNull(stored.contactPhoto)
        }
}
