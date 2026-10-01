// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.pingme.app.inbox.DemoInbox
import org.pingme.core.connector.chat
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.ThemeMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** Obscured chats: blurred until tapped, blurred again soon after, kept out of screenshots (UI_DESIGN.md 10.10). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class ObscuredScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

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
            demo.chats.update(demo.account.id.chat("sam")) { it.copy(isObscured = true) }
        }
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

    private fun visible(text: String) =
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun hidden() = compose.onAllNodesWithContentDescription(HIDDEN, substring = true)

    @Test
    fun messagesStayHiddenUntilTappedThenHideAgain() {
        waitFor { hidden().fetchSemanticsNodes().isNotEmpty() }
        assertTrue("no message text is readable", !visible("Are you still coming tonight?"))
        val before = hidden().fetchSemanticsNodes().size
        hidden().onFirst().performClick()
        waitFor { hidden().fetchSemanticsNodes().size == before - 1 }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(REVEAL_MS + 100))
        waitFor { hidden().fetchSemanticsNodes().size == before }
    }

    @Test
    fun theScreenIsKeptOutOfScreenshots() {
        waitFor { shadowOf(compose.activity.window).getFlag(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    private companion object {
        const val TIMEOUT = 15_000L
        const val HIDDEN = "Hidden message"
        val STEP: Duration = Duration.ofMillis(50)
    }
}
