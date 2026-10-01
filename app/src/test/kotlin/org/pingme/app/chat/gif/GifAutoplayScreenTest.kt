// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.gif

import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.pingme.app.chat.ChatRoute
import org.pingme.app.chat.ChatViewModel
import org.pingme.app.inbox.DemoInbox
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.ThemeMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** With autoplay off, a received GIF waits still behind a GIF mark until tapped (UI_DESIGN.md 5.5). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GifAutoplayScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var demo: DemoInbox
    private lateinit var vm: ChatViewModel

    @Before
    fun setUp() {
        demo = DemoInbox(temp.root)
        demo.controls.update { it.copy(liveActivity = false) }
        runBlocking {
            demo.seed()
            demo.settings.updateApp { it.copy(media = it.media.copy(gifsAutoplay = false)) }
        }
        vm = demo.chatViewModel("taylor")
        compose.setContent {
            PingMeTheme(Appearance(mode = ThemeMode.LIGHT)) { ChatRoute(vm.chatId, onBack = {}, viewModel = vm) }
        }
    }

    @After
    fun tearDown() = demo.close()

    private fun waitFor(condition: () -> Boolean) =
        compose.waitUntil(TIMEOUT) {
            shadowOf(Looper.getMainLooper()).idleFor(STEP)
            condition()
        }

    private fun marked() = compose.onAllNodesWithText(GIF_MARK).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aGifPlaysOnlyOnceTapped() {
        waitFor { marked() }
        compose.onNodeWithText(GIF_MARK).performSemanticsAction(SemanticsActions.OnClick)
        waitFor { !marked() }
    }

    private companion object {
        const val TIMEOUT = 15_000L
        const val GIF_MARK = "GIF"
        val STEP: Duration = Duration.ofMillis(50)
    }
}
