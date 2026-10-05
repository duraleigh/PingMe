// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pingme.core.connector.person
import org.pingme.core.model.AccountId
import org.pingme.core.model.ChatKind
import org.pingme.core.model.NetworkId
import org.pingme.core.model.Person

/** The avatar in an Instagram chat opens the person's profile (owner, 2026-10-05). */
class InstagramProfileTest {
    private val ig = AccountId("ig")
    private val me = ig.person("100")
    private val sam = ig.person("200")

    private fun person(handle: String) = Person(sam, ig, "Sam", null, handle, null, null)

    @Test
    fun theOtherPersonsUsernameMakesThePage() {
        val url =
            instagramProfileUrl(ChatKind.DIRECT, listOf(me, sam), NetworkId.INSTAGRAM, ig, me, listOf(person("sam_o")))
        assertEquals("https://www.instagram.com/sam_o/", url)
    }

    @Test
    fun noPageForGroupsOtherNetworksOrNumericIds() {
        val people = listOf(person("sam_o"))
        assertNull(instagramProfileUrl(ChatKind.GROUP, listOf(me, sam), NetworkId.INSTAGRAM, ig, me, people))
        assertNull(instagramProfileUrl(ChatKind.DIRECT, listOf(me, sam), NetworkId.WHATSAPP, ig, me, people))
        assertNull(
            instagramProfileUrl(ChatKind.DIRECT, listOf(me, sam), NetworkId.INSTAGRAM, ig, me, listOf(person("200"))),
        )
    }
}
