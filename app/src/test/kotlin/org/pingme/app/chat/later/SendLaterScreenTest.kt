// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.later

import android.os.Looper
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.pingme.app.chat.COMPOSER
import org.pingme.app.chat.ChatRoute
import org.pingme.app.chat.ChatViewModel
import org.pingme.app.inbox.DemoInbox
import org.pingme.core.connector.chat
import org.pingme.core.model.MessageStatus
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.ThemeMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** Send later from the send button's menu, the waiting bubble, and Send now (UI_DESIGN.md 10.13). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class SendLaterScreenTest {
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

    private fun latest() =
        runBlocking {
            demo.messages
                .latest(demo.account.id.chat("sam"), 1)
                .first()
                .first()
        }

    @Test
    fun aMessageCanWaitAndThenGoAtOnce() {
        compose.onNode(hasTestTag(COMPOSER)).performTextInput("See you soon")
        compose.onNode(hasContentDescription("Send")).performTouchInput { longClick() }
        compose.onNodeWithText("Send later").performClick()
        compose.onNode(hasTestTag(LATER_FIELD)).performTextInput("in 2 hours")
        compose.onNodeWithText("Schedule").performClick()
        waitFor { latest().status is MessageStatus.Scheduled }
        // The wake-up is set just after the message is stored.
        waitFor { demo.alarm.armed > 0 }
        waitFor { compose.onAllNodesWithText("Sends ", substring = true).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("See you soon", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithText("Send now").performClick()
        waitFor {
            latest().let {
                it.body == "See you soon" && it.status !is MessageStatus.Scheduled &&
                    !it.id.value.contains("pending")
            }
        }
    }

    @Test
    fun aWaitingMessageOffersItsOwnActionsAndUnschedulingDeletesIt() {
        compose.onNode(hasTestTag(COMPOSER)).performTextInput("Happy birthday")
        compose.onNode(hasContentDescription("Send")).performTouchInput { longClick() }
        compose.onNodeWithText("Send later").performClick()
        compose.onNode(hasTestTag(LATER_FIELD)).performTextInput("in 2 hours")
        compose.onNodeWithText("Schedule").performClick()
        waitFor { latest().status is MessageStatus.Scheduled }
        val waiting = latest()
        waitFor { compose.onAllNodesWithText("Sends ", substring = true).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Happy birthday", useUnmergedTree = true).performTouchInput { longClick() }
        listOf("Edit", "Reschedule", "Send now", "Copy", "Unschedule").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNodeWithText("Reply").assertDoesNotExist()
        compose.onNodeWithText("Unschedule").performClick()
        waitFor { runBlocking { demo.messages.get(waiting.id) } == null }
    }

    private companion object {
        const val TIMEOUT = 15_000L
        val STEP: Duration = Duration.ofMillis(50)
    }
}
