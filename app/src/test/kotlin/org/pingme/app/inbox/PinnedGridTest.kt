// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.inbox

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pingme.core.model.AvatarSource
import org.pingme.core.model.Chat
import org.pingme.core.model.ChatId
import org.pingme.core.model.ChatKind
import org.pingme.core.model.NetworkId
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.ThemeMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.time.Instant

/** The pinned grid's layout and unread look (owner, Gate G2). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class PinnedGridTest {
    @get:Rule
    val compose = createComposeRule()

    private fun row(
        name: String,
        unread: Int = 0,
    ) = ChatRow(
        Chat(
            ChatId(name),
            org.pingme.core.model
                .AccountId("a"),
            ChatKind.DIRECT,
            name,
            emptyList(),
            unread,
            Instant.parse("2026-09-30T12:00:00Z"),
            true,
            0,
            false,
            null,
            false,
            false,
            false,
            null,
            null,
            null,
            AvatarSource.Initials,
            null,
            null,
            name,
        ),
        NetworkId.GMESSAGES,
        null,
        typing = false,
    )

    private fun show(vararg names: String) {
        compose.setContent {
            PingMeTheme(Appearance(mode = ThemeMode.LIGHT)) {
                PinnedGrid(names.map { row(it, unread = if (it == "Unread") 1 else 0) }, {}, {})
            }
        }
    }

    private val side = 12.dp

    private fun centreOf(name: String) = compose.onNodeWithText(name).getBoundsInRoot().let { (it.left + it.right) / 2 }

    @Test
    fun oneOrTwoPinsSitCentred() {
        show("Ana", "Ben")
        val root = compose.onRoot().getBoundsInRoot()
        val middle = (root.left + root.right) / 2
        val left = centreOf("Ana")
        val right = centreOf("Ben")
        assertTrue("Ana at $left, Ben at $right, middle $middle", abs(((left + right) / 2 - middle).value) < 2f)
        assertTrue("the pair is close together", (right - left) < 160.dp)
    }

    @Test
    fun threeOrFourPinsShareTheWidth() {
        show("Ana", "Ben", "Cal", "Dee")
        val root = compose.onRoot().getBoundsInRoot()
        // The grid keeps a 12 dp margin each side; the tiles share what is left.
        val left = root.left + side
        val width = root.right - root.left - side * 2
        val names = listOf("Ana", "Ben", "Cal", "Dee")
        names.forEachIndexed { i, name ->
            val expected = left + width * ((i + 0.5f) / names.size)
            assertTrue("$name at ${centreOf(name)}, expected $expected", abs((centreOf(name) - expected).value) < 3f)
        }
    }

    @Test
    fun moreThanFiveWrapAtFive() {
        show("Ana", "Ben", "Cal", "Dee", "Eve", "Fay")
        val top = compose.onNodeWithText("Ana").getBoundsInRoot()
        val wrapped = compose.onNodeWithText("Fay").getBoundsInRoot()
        assertTrue("Fay is on the second line", wrapped.top > top.bottom)
        assertTrue("Fay sits under Ana", abs((centreOf("Fay") - centreOf("Ana")).value) < 2f)
    }

    @Test
    fun anUnreadTileCarriesTheUnreadMark() {
        show("Ana", "Unread", "Cal")
        assertEquals(1, compose.onAllNodes(hasContentDescription("Unread messages")).fetchSemanticsNodes().size)
    }
}
