// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.pingme.core.model.AccountId
import org.pingme.core.model.ChatId
import org.pingme.core.model.NetworkId

class NetworkSwipeTest {
    private fun chip(
        network: NetworkId,
        default: Boolean = false,
    ) = MemberChip(ChatId(network.name), AccountId(network.name), network, network.name, true, default)

    private val gm = chip(NetworkId.GMESSAGES)
    private val whatsapp = chip(NetworkId.WHATSAPP, default = true)
    private val instagram = chip(NetworkId.INSTAGRAM)

    @Test
    fun theDefaultComesFirstAndTheOrderIgnoresRecentActivity() {
        val one = swipeOrder(listOf(gm, whatsapp, instagram))
        val other = swipeOrder(listOf(instagram, gm, whatsapp))
        assertEquals(whatsapp, one.first())
        assertEquals(one, other)
    }

    @Test
    fun aSwipeGoesRoundTheEndsBothWays() {
        val members = listOf(gm, whatsapp, instagram)
        val order = swipeOrder(members)
        assertEquals(order[1], nextMember(members, order[0], towardsNext = true))
        assertEquals(order[0], nextMember(members, order[2], towardsNext = true))
        assertEquals(order[2], nextMember(members, order[0], towardsNext = false))
    }

    @Test
    fun oneNetworkHasNothingToSwipeTo() {
        assertNull(nextMember(listOf(gm), gm, towardsNext = true))
    }
}
