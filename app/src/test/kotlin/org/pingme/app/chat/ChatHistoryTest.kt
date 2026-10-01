// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.pingme.app.inbox.DemoInbox
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.ThemeMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A chat with nothing stored yet, as every chat is right after a login, loads its history as
 * soon as it opens. It once waited for a scroll to the top, which an empty list never has.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class ChatHistoryTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var demo: DemoInbox

    @Before
    fun setUp() {
        demo = DemoInbox(temp.root)
        demo.controls.update { it.copy(liveActivity = false) }
        runBlocking { demo.seed(history = false) }
    }

    @After
    fun tearDown() = demo.close()

    @Test
    fun anEmptyChatLoadsItsHistoryWhenOpened() {
        val vm = demo.chatViewModel("sam")
        assertTrue(runBlocking { demo.messages.oldest(vm.chatId) } == null)
        compose.setContent {
            PingMeTheme(Appearance(mode = ThemeMode.LIGHT)) { ChatRoute(vm.chatId, onBack = {}, viewModel = vm) }
        }
        compose.waitUntil(TIMEOUT) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(STEP_MS))
            vm.state.value.items
                .any { it is ChatItem.Bubble }
        }
    }

    private companion object {
        const val TIMEOUT = 15_000L
        const val STEP_MS = 50L
    }
}
