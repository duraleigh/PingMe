// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.appearance

import org.junit.Assert.assertEquals
import org.junit.Test
import org.pingme.core.model.NetworkId
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.BubbleStyle
import org.pingme.core.ui.theme.ShapeFamily

/** The one-time Google Messages look, round by round (owner, 2026-11-02). */
class MessagesLookTest {
    private val blue = 0xFF5787F3.toInt()
    private val green = 0xFF25D366.toInt()

    @Test
    fun roundThreeClearsOnlyThePickedGoogleMessagesColour() {
        val before =
            Appearance(
                lookVersion = 2,
                bubbleStyle = BubbleStyle.GRADIENT,
                bubbleTails = true,
                shapeFamily = ShapeFamily.EXPRESSIVE,
                networkColors = mapOf(NetworkId.GMESSAGES to blue, NetworkId.WHATSAPP to green),
            )
        val after = before.withMessagesLook()
        assertEquals(before.copy(networkColors = mapOf(NetworkId.WHATSAPP to green), lookVersion = 3), after)
    }

    @Test
    fun aColourPickedAfterTheLookStaysPicked() {
        val picked = Appearance(lookVersion = 3, networkColors = mapOf(NetworkId.GMESSAGES to blue))
        assertEquals(picked, picked.withMessagesLook())
    }

    @Test
    fun aFirstLookKeepsTheGradientTailsAndPictureShapes() {
        val after = Appearance(shapeFamily = ShapeFamily.EXPRESSIVE).withMessagesLook()
        assertEquals(BubbleStyle.GRADIENT, after.bubbleStyle)
        assertEquals(true, after.bubbleTails)
        assertEquals(ShapeFamily.EXPRESSIVE, after.shapeFamily)
        assertEquals(3, after.lookVersion)
    }
}
