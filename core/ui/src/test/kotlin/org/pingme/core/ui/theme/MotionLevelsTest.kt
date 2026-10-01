// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material3.MotionScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each motion level looks different for screens and new bubbles, and Off is still (UI_DESIGN.md 4.5). */
class MotionLevelsTest {
    private val scheme = MotionScheme.expressive()
    private val moving = listOf(MotionIntensity.SUBTLE, MotionIntensity.FULL, MotionIntensity.EXTRA)

    @Test
    fun offIsInstantAndTheOtherLevelsEachMoveTheirOwnWay() {
        assertEquals(EnterTransition.None, ScreenMotion.enter(MotionIntensity.OFF, scheme))
        assertEquals(ExitTransition.None, ScreenMotion.exit(MotionIntensity.OFF, scheme))
        val enters = moving.map { ScreenMotion.enter(it, scheme) }
        val exits = moving.map { ScreenMotion.exit(it, scheme) }
        enters.forEach { assertNotEquals(EnterTransition.None, it) }
        exits.forEach { assertNotEquals(ExitTransition.None, it) }
        assertEquals("every level differs on the way in", 3, enters.toSet().size)
        assertEquals("every level differs on the way out", 3, exits.toSet().size)
    }

    @Test
    fun goingBackMirrorsGoingForward() {
        moving.drop(1).forEach {
            val forward = ScreenMotion.enter(it, scheme, fromEnd = true)
            assertNotEquals(forward, ScreenMotion.enter(it, scheme, fromEnd = false))
            val back = ScreenMotion.exit(it, scheme, toEnd = true)
            assertNotEquals(back, ScreenMotion.exit(it, scheme, toEnd = false))
        }
    }

    @Test
    fun newBubblesComeInLouderAsTheLevelRises() {
        assertNull(BubbleEntrance.at(MotionIntensity.OFF))
        val (subtle, full, extra) = moving.map { BubbleEntrance.at(it)!! }
        assertEquals("Subtle only fades", 1f, subtle.fromScale)
        assertEquals(0f, subtle.riseDp)
        assertTrue(full.fromScale < subtle.fromScale && extra.fromScale < full.fromScale)
        assertTrue(full.riseDp > subtle.riseDp && extra.riseDp > full.riseDp)
        assertTrue("Extra bounces most", extra.dampingRatio < full.dampingRatio)
        assertTrue(full.dampingRatio < subtle.dampingRatio)
    }
}
