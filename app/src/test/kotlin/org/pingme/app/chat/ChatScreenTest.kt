// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.pingme.app.assertAccessible
import org.pingme.app.inbox.DemoInbox
import org.pingme.core.connector.chat
import org.pingme.core.model.Quote
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.ThemeMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The chat screen against the demo network (BUILD_PLAN.md P2.4, P2.8). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class ChatScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var demo: DemoInbox
    private var appearance by mutableStateOf(Appearance(mode = ThemeMode.LIGHT))

    @Before
    fun setUp() {
        demo = DemoInbox(temp.root)
        runBlocking { demo.seed() }
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

    private fun open(
        remote: String,
        vm: ChatViewModel = demo.chatViewModel(remote),
    ): ChatViewModel {
        compose.setContent { PingMeTheme(appearance) { ChatRoute(vm.chatId, onBack = {}, viewModel = vm) } }
        waitFor {
            vm.state.value.items
                .isNotEmpty()
        }
        return vm
    }

    private fun sam() = demo.account.id.chat("sam")

    @Test
    fun openingShowsTheConversationAndMarksItRead() {
        open("sam")
        waitForText("I saved you a seat")
        waitForText("Sam Ortiz")
        compose.onNodeWithText("New messages").assertExists()
        waitFor { runBlocking { demo.chats.get(sam())!!.unreadCount } == 0 }
    }

    @Test
    fun whatYouTypeIsSentAndShows() {
        open("sam")
        compose.onNode(hasTestTag(COMPOSER)).performTextInput("See you at 7")
        compose.onNodeWithContentDescription("Send").performClick()
        waitForText("See you at 7")
        waitFor {
            runBlocking {
                demo.messages
                    .latest(sam(), 1)
                    .first()
                    .single()
                    .body
            } == "See you at 7"
        }
    }

    @Test
    fun aReplyShowsItsStripAndCarriesTheQuote() {
        val vm = open("sam")
        val theirs =
            runBlocking {
                demo.messages
                    .latest(sam(), 20)
                    .first()
                    .first { !it.isOutgoing }
            }
        vm.reply(theirs)
        waitForText("Replying to Sam Ortiz")
        compose.onNode(hasTestTag(COMPOSER)).performTextInput("Yes!")
        compose.onNodeWithContentDescription("Send").performClick()
        waitFor { compose.onAllNodesWithText("Replying to Sam Ortiz").fetchSemanticsNodes().isEmpty() }
        waitFor {
            runBlocking {
                demo.messages
                    .latest(sam(), 1)
                    .first()
                    .single()
                    .quote
            } ==
                Quote("Sam Ortiz", theirs.body!!)
        }
    }

    @Test
    fun aPinnedMessageShowsItsBannerAndUnpins() {
        val vm = open("sam")
        val message =
            runBlocking {
                demo.messages
                    .latest(sam(), 1)
                    .first()
                    .single()
            }
        runBlocking { demo.messageActions.pin(message) }
        waitForText("Pinned")
        compose.onNodeWithContentDescription("Unpin").performClick()
        waitFor {
            vm.state.value.pinned
                .isEmpty()
        }
    }

    @Test
    fun groupsShowWhoSaidWhat() {
        open("design")
        waitForText("Priya Shah")
        compose.onNode(hasText("people", substring = true)).assertExists()
    }

    /** Renders chats to build/screenshots/chat-*.png. */
    @Test
    fun screenshots() {
        val vm = open("design")
        waitFor { vm.state.value.items.size > 3 }
        compose.waitForIdle()
        save("chat-group-light")
        appearance = appearance.copy(mode = ThemeMode.DARK)
        compose.waitForIdle()
        save("chat-group-dark")
    }

    private fun save(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File("build/screenshots").apply { mkdirs() }.resolve("$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private companion object {
        const val TIMEOUT = 15_000L
        const val STEP_MS = 50L
    }

    @Test
    fun everyControlHasASpokenNameAndIsBigEnough() {
        open("sam")
        compose.assertAccessible()
    }
}
