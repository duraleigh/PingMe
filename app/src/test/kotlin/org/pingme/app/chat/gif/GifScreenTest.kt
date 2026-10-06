// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.gif

import android.os.Looper
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildAt
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.pingme.app.assertAccessible
import org.pingme.app.chat.ChatRoute
import org.pingme.app.chat.ChatViewModel
import org.pingme.app.inbox.DemoInbox
import org.pingme.connectors.demo.DemoControls
import org.pingme.core.connector.chat
import org.pingme.core.model.MessageKind
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.ThemeMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** The GIF button, Trending, sending a GIF, and Shrink for MMS (UI_DESIGN.md 5.5). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class GifScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var demo: DemoInbox
    private lateinit var vm: ChatViewModel

    private fun open(settings: (DemoControls.Settings) -> DemoControls.Settings = { it }) {
        demo = DemoInbox(temp.root)
        demo.controls.update { settings(it.copy(liveActivity = false)) }
        demo.gifStore.gifs = listOf(GifTest.gif("dance", "Happy dance"))
        runBlocking { demo.seed() }
        vm = demo.chatViewModel("sam")
        compose.setContent {
            PingMeTheme(Appearance(mode = ThemeMode.LIGHT)) { ChatRoute(vm.chatId, onBack = {}, viewModel = vm) }
        }
        waitFor {
            vm.state.value.items
                .isNotEmpty()
        }
    }

    @After
    fun tearDown() = demo.close()

    private fun waitFor(condition: () -> Boolean) =
        compose.waitUntil(TIMEOUT) {
            shadowOf(Looper.getMainLooper()).idleFor(STEP)
            condition()
        }

    private fun hasText(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun latest() =
        runBlocking {
            demo.messages
                .latest(demo.account.id.chat("sam"), 1)
                .first()
                .first()
        }

    private fun pickFromTrending() {
        // GIFs live in the + menu (owner, 2026-10-03).
        compose.onNode(hasContentDescription("Attach")).performClick()
        waitFor { hasText("GIF") }
        compose.onNodeWithText("GIF").performClick()
        waitFor { hasText("No favourites yet. Hold a GIF to keep it here.") }
        compose.onNodeWithText("Trending").performClick()
        waitFor { compose.onAllNodes(hasTestTag(GIF_GRID)).fetchSemanticsNodes().isNotEmpty() }
        // The GIF picker with its grid, as a screen reader sees it (P8.1).
        compose.assertAccessible()
        compose.onNode(hasTestTag(GIF_GRID)).onChildAt(0).performClick()
    }

    @Test
    fun aGifFromTrendingIsSent() {
        open()
        pickFromTrending()
        waitFor { latest().let { it.kind == MessageKind.GIF && !it.id.value.contains("pending") } }
        assertEquals(listOf("trending"), demo.gifStore.queries)
    }

    @Test
    fun aBigGifOnMmsCanBeShrunk() {
        open { it.copy(capabilities = DemoControls.MINIMAL) }
        demo.gifStore.bytes = 2_000_000
        pickFromTrending()
        waitFor { hasText("This may be too big for MMS") }
        compose.onNodeWithText("Shrink").performClick()
        waitFor { latest().kind == MessageKind.GIF }
        val sent = latest().attachments.single()
        assertEquals(demo.gifStore.smallBytes.toLong(), File(sent.localPath!!).length())
    }

    private companion object {
        const val TIMEOUT = 15_000L
        val STEP: Duration = Duration.ofMillis(50)
    }
}
