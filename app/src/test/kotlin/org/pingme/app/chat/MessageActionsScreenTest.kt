// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import android.os.Looper
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.pingme.app.inbox.DemoInbox
import org.pingme.core.connector.chat
import org.pingme.core.model.Message
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.ThemeMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Press and hold, reactions, delete, edit, select, and swipe to reply, against the demo
 * network (UI_DESIGN.md 3.3, 5.x).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class MessageActionsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var demo: DemoInbox
    private lateinit var vm: ChatViewModel
    private val sam get() = demo.account.id.chat("sam")

    @Before
    fun setUp() {
        demo = DemoInbox(temp.root)
        // Nobody messages on their own, so nothing new pushes the test's message off screen mid-test.
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
            // Moving the clock on, not just running what is due, keeps slow machines from stalling here.
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(STEP_MS))
            condition()
        }

    private fun waitForText(text: String) =
        waitFor { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun latest(): List<Message> = runBlocking { demo.messages.latest(sam, 20).first() }

    private fun stored(message: Message) = runBlocking { demo.messages.get(message.id) }

    // A just-sent bubble can briefly be missing from the merged tree while the list settles, so this
    // holds the message's own text, which sits inside the bubble.
    private fun holdOwn(text: String) =
        compose.onNodeWithText(text, useUnmergedTree = true).performTouchInput { longClick() }

    private fun theirs() = latest().first { !it.isOutgoing && !it.body.isNullOrBlank() }

    private fun send(text: String): Message {
        compose.onNode(hasTestTag(COMPOSER)).performTextInput(text)
        compose.onNode(hasContentDescription("Send")).performClick()
        // Waits for the network's copy to replace the "sending" placeholder, so the bubble on screen stays put.
        waitFor {
            latest().firstOrNull()?.let { it.body == text && it.isOutgoing && !it.id.value.startsWith("pending-") } ==
                true
        }
        // The "sending" bubble fades out as the sent one fades in; wait until only the sent one is left and showing.
        waitFor {
            compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size == 1 &&
                compose.onAllNodesWithText(text).fetchSemanticsNodes().size == 1
        }
        return latest().first()
    }

    @Test
    fun holdingShowsTheBarAndActionsAndReacts() {
        val message = theirs()
        compose.onNodeWithText(message.body!!).performTouchInput { longClick() }
        waitForText("Reply")
        listOf(
            "Copy",
            "Forward",
            "Pin",
            "Select",
            "Info",
            "Delete",
        ).forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNode(hasContentDescription("React with 😂")).performClick()
        waitFor { stored(message)!!.reactions.any { it.emoji == "😂" } }
    }

    @Test
    fun doubleTapSendsTheDoubleTapReaction() {
        val message = theirs()
        compose.onNodeWithText(message.body!!).performTouchInput { doubleClick() }
        waitFor { stored(message)!!.reactions.any { it.emoji == "❤️" } }
    }

    @Test
    fun deleteForMeHidesAndDeleteForEveryoneLeavesAPlaceholder() {
        val mine = send("delete me")
        holdOwn("delete me")
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete for everyone").performClick()
        waitFor { stored(mine)?.deletedForEveryone == true }

        val other = theirs()
        compose.onNodeWithText(other.body!!).performTouchInput { longClick() }
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Only for your own messages").assertExists()
        compose.onNodeWithText("Delete for me").performClick()
        waitFor {
            vm.state.value.items
                .none { (it as? ChatItem.Bubble)?.message?.id == other.id }
        }
        waitForText("Undo")
    }

    @Test
    fun yourOwnMessageCanBeEdited() {
        val mine = send("helo there")
        holdOwn("helo there")
        compose.onNodeWithText("Edit").performClick()
        waitForText("Editing message")
        compose.onNode(hasTestTag(COMPOSER)).performTextClearance()
        compose.onNode(hasTestTag(COMPOSER)).performTextInput("hello there")
        compose.onNode(hasContentDescription("Send")).performClick()
        waitFor { stored(mine)?.body == "hello there" }
        assertTrue(stored(mine)!!.editedAt != null)
    }

    @Test
    fun selectingSwapsInTheToolbar() {
        val message = theirs()
        compose.onNodeWithText(message.body!!).performTouchInput { longClick() }
        compose.onNodeWithText("Select").performClick()
        waitForText("1 selected")
        compose.onNode(hasContentDescription("Copy")).performClick()
        waitFor {
            vm.state.value.selection
                .isEmpty()
        }
    }

    @Test
    fun swipingRightStartsAReply() {
        val message = theirs()
        compose.onNodeWithText(message.body!!).performTouchInput { swipeRight() }
        waitForText("Replying to Sam Ortiz")
        assertEquals(
            message.id,
            vm.state.value.replyTo
                ?.id,
        )
    }

    private companion object {
        const val TIMEOUT = 15_000L
        const val STEP_MS = 50L
    }
}
