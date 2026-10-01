// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pingme.core.ui.theme.MotionIntensity
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The Reaction Burst (UI_DESIGN.md 5.4): lands, celebrates, and is cut down by the motion level. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h400dp-xhdpi")
class ReactionBurstTest {
    @get:Rule
    val compose = createComposeRule()

    private fun run(motion: MotionIntensity): Pair<BurstState, MutableList<Burst>> {
        val state = BurstState()
        val landed = mutableListOf<Burst>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.size(400.dp).background(Color.White)) {
                ReactionBurstLayer(state, motion, Color(0xFF4059AD), Color(0xFFE0457B), emptySet(), onLand = {
                    landed +=
                        it
                })
            }
        }
        state.play("🎉", BurstKind.PICKED, to = Offset(500f, 500f), from = Offset(300f, 150f), key = "m1")
        // Robolectric runs the main thread only when asked; a phone does this by itself.
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
        return state to landed
    }

    @Test
    fun aPickedReactionLandsThenCelebratesThenGoes() {
        val (state, landed) = run(MotionIntensity.EXTRA)
        advance(BurstTiming.PICK + BurstTiming.LAND + 300)
        assertEquals(listOf("m1"), landed.map { it.key })
        assertEquals("still celebrating", 1, state.bursts.size)
        save("burst-celebrate")
        advance(BurstTiming.CELEBRATE)
        assertTrue(state.bursts.isEmpty())
    }

    @Test
    fun subtleStopsAfterLandingAndOffStillLands() {
        val (state, landed) = run(MotionIntensity.SUBTLE)
        advance(BurstTiming.PICK + BurstTiming.LAND + 50)
        assertEquals(1, landed.size)
        assertTrue("no celebration at Subtle", state.bursts.isEmpty())
    }

    /** Moves time on a frame at a time, letting the main thread run between frames. */
    private fun advance(millis: Long) {
        repeat((millis / FRAME).toInt() + 1) {
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun save(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File("build/screenshots").apply { mkdirs() }.resolve("$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private companion object {
        const val FRAME = 16L
    }
}
