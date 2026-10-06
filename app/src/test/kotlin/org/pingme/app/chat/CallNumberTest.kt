// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pingme.core.connector.person
import org.pingme.core.model.AccountId
import org.pingme.core.model.ChatKind
import org.pingme.core.model.Person

/** The call icons dial the other person, never the owner (owner, 2026-10-06). */
class CallNumberTest {
    private val gm = AccountId("gm")
    private val meId = gm.person("me-1")
    private val samId = gm.person("sam")

    private fun person(
        id: org.pingme.core.model.PersonId,
        name: String,
        number: String?,
    ) = Person(id, gm, name, number, number ?: id.value, null, null)

    private val everyone = listOf(person(meId, "You", "+15550000001"), person(samId, "Sam", "+15550000002"))

    @Test
    fun theOtherPersonsNumberEvenWhenTheOwnerIsListedFirst() {
        assertEquals("+15550000002", callNumber(ChatKind.DIRECT, listOf(meId, samId), gm, meId, everyone))
    }

    @Test
    fun theOwnerIsSkippedByNameWhenTheirIdIsNotKnownYet() {
        // Before the owner has sent anything, "me" is a placeholder id that matches no participant.
        assertEquals("+15550000002", callNumber(ChatKind.DIRECT, listOf(meId, samId), gm, SelfId.of(gm), everyone))
    }

    @Test
    fun groupsAndChatsWithNoOtherNumberGiveNothing() {
        assertNull(callNumber(ChatKind.GROUP, listOf(meId, samId), gm, meId, everyone))
        assertNull(callNumber(ChatKind.DIRECT, listOf(meId), gm, meId, everyone))
    }
}
