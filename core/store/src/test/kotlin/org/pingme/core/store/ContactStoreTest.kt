// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.store

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pingme.core.model.AccountId
import org.pingme.core.model.ContactId
import org.pingme.core.model.MergeLink
import org.pingme.core.model.PersonId
import kotlin.time.Duration.Companion.minutes

class ContactStoreTest : StoreTest() {
    @Test
    fun peopleRoundTripAndListByAccount() =
        runTest {
            accounts.upsert(account("a"))
            val sam = person("sam", "a", "Sam Ortiz", phone = "+15555550123").copy(contactId = ContactId("42"))
            contacts.upsert(sam)
            contacts.upsert(person("dad", "a", "Dad"))
            assertEquals(sam, contacts.person(PersonId("sam")))
            assertEquals(listOf("Dad", "Sam Ortiz"), contacts.people(AccountId("a")).first().map { it.displayName })
            assertEquals(listOf(sam), contacts.byContact(ContactId("42")).first())
            contacts.delete(PersonId("sam"))
            assertNull(contacts.observePerson(PersonId("sam")).first())
        }

    @Test
    fun samePhoneNumberAcrossAccountsIsFound() =
        runTest {
            accounts.upsert(account("rcs"))
            accounts.upsert(account("wa"))
            contacts.upsert(person("rcs-sam", "rcs", "Sam", phone = "+15555550123"))
            contacts.upsert(person("wa-sam", "wa", "Sam O", phone = "+15555550123"))
            contacts.upsert(person("wa-dad", "wa", "Dad", phone = "+15555550199"))
            assertEquals(setOf("rcs-sam", "wa-sam"), contacts.byPhoneNumber("+15555550123").map { it.id.value }.toSet())
        }

    @Test
    fun mergeLinksAreRecordedOnlyWhenConfirmedAndCanBeRemoved() =
        runTest {
            assertEquals(emptyList<MergeLink>(), contacts.mergeLinks().first())
            val rcs = MergeLink(PersonId("rcs-sam"), ContactId("42"), now)
            val wa = MergeLink(PersonId("wa-sam"), ContactId("42"), now + 1.minutes)
            contacts.confirmLink(rcs)
            contacts.confirmLink(wa)
            assertEquals(listOf(rcs, wa), contacts.mergeLinks().first())
            assertEquals(listOf(rcs, wa), contacts.linksForContact(ContactId("42")))
            assertEquals(listOf(wa), contacts.linksForPerson(PersonId("wa-sam")))
            contacts.removeLink(PersonId("wa-sam"), ContactId("42"))
            assertEquals(listOf(rcs), contacts.linksForContact(ContactId("42")))
        }
}
