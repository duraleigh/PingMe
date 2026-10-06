// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.chat.attach

import android.os.Looper
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import org.pingme.app.assertAccessible
import org.pingme.app.chat.COMPOSER
import org.pingme.app.chat.ChatRoute
import org.pingme.app.chat.ChatViewModel
import org.pingme.app.inbox.DemoInbox
import org.pingme.core.connector.OutgoingAttachment
import org.pingme.core.connector.chat
import org.pingme.core.model.AttachmentKind
import org.pingme.core.model.MessageKind
import org.pingme.core.ui.theme.Appearance
import org.pingme.core.ui.theme.PingMeTheme
import org.pingme.core.ui.theme.ThemeMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The attach sheet, the strip of what waits to go, and sending it (UI_DESIGN.md 5.8). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class AttachScreenTest {
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

    // Moves the main thread's clock on as it waits, so the demo network's "upload" finishes.
    private fun waitFor(condition: () -> Boolean) =
        compose.waitUntil(TIMEOUT) {
            shadowOf(Looper.getMainLooper()).idleFor(STEP)
            condition()
        }

    @Test
    fun thePlusButtonOffersEveryWayToAttach() {
        compose.onNode(hasContentDescription("Attach")).performClick()
        for (option in listOf("Camera", "Gallery", "File", "Location", "Contact")) {
            waitFor { compose.onAllNodesWithText(option).fetchSemanticsNodes().isNotEmpty() }
        }
        // The open attach sheet, as a screen reader sees it (P8.1).
        compose.assertAccessible()
    }

    @Test
    fun aStagedFileGoesWithTheCaptionAndLeavesTheStrip() {
        val report = temp.newFile("report.pdf").apply { writeBytes(ByteArray(BYTES)) }
        vm.outbox.add(OutgoingAttachment(report.path, "application/pdf", AttachmentKind.FILE, "report.pdf", null))
        waitFor { compose.onAllNodesWithText("report.pdf").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasTestTag(COMPOSER)).performTextInput("Here it is")
        compose.onNode(hasContentDescription("Send")).performClick()
        waitFor {
            latestOutgoing()?.let {
                it.kind == MessageKind.FILE &&
                    it.id.value
                        .contains("pending")
                        .not()
            } == true
        }
        val sent = latestOutgoing()!!
        assertEquals("Here it is", sent.body)
        assertEquals("report.pdf", sent.attachments.single().fileName)
        assertTrue(
            "the strip empties",
            vm.outbox.staged.value
                .isEmpty(),
        )
    }

    @Test
    fun takingAStagedFileBackLeavesNothingToSend() {
        val photo = temp.newFile("cat.jpg").apply { writeBytes(ByteArray(BYTES)) }
        vm.outbox.add(OutgoingAttachment(photo.path, "image/jpeg", AttachmentKind.IMAGE, "cat.jpg", null))
        waitFor { compose.onAllNodes(hasContentDescription("Remove")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasContentDescription("Remove")).performClick()
        waitFor {
            vm.outbox.staged.value
                .isEmpty()
        }
        // With nothing left to send, the button is the microphone again.
        waitFor { compose.onAllNodes(hasTestTag(org.pingme.app.chat.voice.MIC)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun latestOutgoing() =
        runBlocking {
            demo.messages
                .latest(sam, 5)
                .first()
                .firstOrNull { it.isOutgoing }
        }

    private companion object {
        const val TIMEOUT = 15_000L
        val STEP: java.time.Duration = java.time.Duration.ofMillis(50)
        const val BYTES = 4096
    }
}
